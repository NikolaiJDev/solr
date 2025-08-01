/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.component;

import static org.apache.solr.common.params.CommonParams.PARTIAL_RESULTS;
import static org.apache.solr.request.SolrQueryRequest.disallowPartialResults;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jcip.annotations.NotThreadSafe;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrResponse;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.LBHttp2SolrClient;
import org.apache.solr.client.solrj.impl.LBSolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.routing.NoOpReplicaListTransformer;
import org.apache.solr.client.solrj.routing.ReplicaListTransformer;
import org.apache.solr.cloud.CloudDescriptor;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.security.AllowListUrlChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Solr's default {@link ShardHandler} implementation; uses Jetty's async HTTP Client APIs for
 * sending requests.
 *
 * <p>Shard-requests triggered by {@link #submit(ShardRequest, String, ModifiableSolrParams)} will
 * be sent synchronously (i.e. before 'submit' returns to the caller). Response waiting and parsing
 * happens asynchronously via {@link HttpShardHandlerFactory#commExecutor}. See {@link
 * HttpShardHandlerFactory} for details on configuring this executor.
 *
 * <p>The ideal choice for collections with modest or moderate sharding.
 */
@NotThreadSafe
public class HttpShardHandler extends ShardHandler {

  @SuppressWarnings("unused")
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  /**
   * If the request context map has an entry with this key and Boolean.TRUE as value, {@link
   * #prepDistributed(ResponseBuilder)} will only include {@link
   * org.apache.solr.common.cloud.Replica.Type#NRT} replicas as possible destination of the
   * distributed request (or a leader replica of type {@link
   * org.apache.solr.common.cloud.Replica.Type#TLOG}). This is used by the RealtimeGet handler,
   * since other types of replicas shouldn't respond to RTG requests
   */
  public static final String ONLY_NRT_REPLICAS = "distribOnlyRealtime";

  /**
   * This is a fake ShardResponse used internally to trigger the {@link #take(boolean)} method to
   * stop waiting and cancel outstanding shard requests.
   */
  private static final ShardResponse CANCELLATION_NOTIFICATION = new ShardResponse();

  private final HttpShardHandlerFactory httpShardHandlerFactory;

  protected final ConcurrentMap<ShardResponse, CompletableFuture<LBSolrClient.Rsp>>
      responseFutureMap;
  protected final BlockingQueue<ShardResponse> responses;
  private final AtomicBoolean canceled = new AtomicBoolean(false);

  private final Map<String, List<String>> shardToURLs;
  protected LBHttp2SolrClient<Http2SolrClient> lbClient;

  public HttpShardHandler(HttpShardHandlerFactory httpShardHandlerFactory) {
    this.httpShardHandlerFactory = httpShardHandlerFactory;
    this.lbClient = httpShardHandlerFactory.loadbalancer;
    this.responses = new LinkedBlockingQueue<>();
    this.responseFutureMap = new ConcurrentHashMap<>();

    // maps "localhost:8983|localhost:7574" to a shuffled
    // List("http://localhost:8983","http://localhost:7574")
    // This is primarily to keep track of what order we should use to query the replicas of a shard
    // so that we use the same replica for all phases of a distributed request.
    shardToURLs = new HashMap<>();
  }

  /**
   * Parse the {@value ShardParams#SHARDS_TOLERANT} param from <code>params</code> as a boolean;
   * accepts {@value ShardParams#REQUIRE_ZK_CONNECTED} as a valid value indicating <code>false
   * </code>.
   *
   * <p>By default, returns <code>false</code> when {@value ShardParams#SHARDS_TOLERANT} is not set
   * in <code>
   * params</code>.
   */
  public static boolean getShardsTolerantAsBool(SolrQueryRequest req) {
    String shardsTolerantValue = req.getParams().get(ShardParams.SHARDS_TOLERANT);
    if (null == shardsTolerantValue
        || shardsTolerantValue.trim().equals(ShardParams.REQUIRE_ZK_CONNECTED)) {
      return false;
    } else {
      boolean tolerant = StrUtils.parseBool(shardsTolerantValue.trim());
      if (tolerant && disallowPartialResults(req.getParams())) {
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST,
            "Use of "
                + ShardParams.SHARDS_TOLERANT
                + " requires that "
                + PARTIAL_RESULTS
                + " is true. If "
                + PARTIAL_RESULTS
                + " is defaulted to false explicitly passing "
                + PARTIAL_RESULTS
                + "=true in the request will allow shards.tolerant to work");
      }
      return tolerant; // throw an exception if non-boolean
    }
  }

  public static class SimpleSolrResponse extends SolrResponse {

    volatile long elapsedTime;

    volatile NamedList<Object> nl;

    @Override
    public long getElapsedTime() {
      return elapsedTime;
    }

    @Override
    public NamedList<Object> getResponse() {
      return nl;
    }

    @Override
    public void setResponse(NamedList<Object> rsp) {
      nl = rsp;
    }

    @Override
    public void setElapsedTime(long elapsedTime) {
      this.elapsedTime = elapsedTime;
    }
  }

  // Not thread safe... don't use in Callable.
  // Don't modify the returned URL list.
  private List<String> getURLs(String shard) {
    List<String> urls = shardToURLs.get(shard);
    if (urls == null) {
      urls = httpShardHandlerFactory.buildURLList(shard);
      shardToURLs.put(shard, urls);
    }
    return urls;
  }

  private LBSolrClient.Req prepareLBRequest(
      ShardRequest sreq, String shard, ModifiableSolrParams params, List<String> urls) {
    params.remove(CommonParams.WT); // use default (currently javabin)
    QueryRequest req = createQueryRequest(sreq, params, shard);
    req.setMethod(SolrRequest.METHOD.POST);
    SolrRequestInfo requestInfo = SolrRequestInfo.getRequestInfo();
    if (requestInfo != null) {
      req.setUserPrincipal(requestInfo.getUserPrincipal());
    }

    return httpShardHandlerFactory.newLBHttpSolrClientReq(req, urls);
  }

  private ShardResponse prepareShardResponse(ShardRequest sreq, String shard) {
    ShardResponse srsp = new ShardResponse();
    if (sreq.nodeName != null) {
      srsp.setNodeName(sreq.nodeName);
    }
    srsp.setShardRequest(sreq);
    srsp.setShard(shard);

    return srsp;
  }

  protected void recordShardSubmitError(ShardResponse srsp, SolrException exception) {
    srsp.setException(exception);
    srsp.setResponseCode(exception.code());

    synchronized (canceled) {
      if (!canceled.get()) {
        responses.add(srsp);
      }
    }
  }

  @Override
  public void submit(ShardRequest sreq, String shard, ModifiableSolrParams params) {
    // Since we are submitting new shard requests, the request is not canceled
    canceled.set(false);
    // do this outside of the callable for thread safety reasons
    final List<String> urls = getURLs(shard);
    final var lbReq = prepareLBRequest(sreq, shard, params, urls);
    final var srsp = prepareShardResponse(sreq, shard);
    final var ssr = new SimpleSolrResponse();
    srsp.setSolrResponse(ssr);
    if (urls.isEmpty()) {
      recordShardSubmitError(
          srsp,
          new SolrException(
              SolrException.ErrorCode.SERVICE_UNAVAILABLE, "no servers hosting shard: " + shard));
      return;
    }
    long startTimeNS = System.nanoTime();

    makeShardRequest(sreq, shard, params, lbReq, ssr, srsp, startTimeNS);
  }

  /**
   * Do the actual work of sending a request to a shard and receiving the response
   *
   * @param sreq the request to make
   * @param shard the shard to address
   * @param params request parameters
   * @param lbReq the load balanced request suitable for LBHttp2SolrClient
   * @param ssr the response collector part 1
   * @param srsp the shard response collector
   * @param startTimeNS the time at which the request was initiated, likely just prior to calling
   *     this method.
   */
  // future work might see if we can reduce this parameter list. For example,
  // why do we need 2 separate response objects?
  protected void makeShardRequest(
      ShardRequest sreq,
      String shard,
      ModifiableSolrParams params,
      LBSolrClient.Req lbReq,
      SimpleSolrResponse ssr,
      ShardResponse srsp,
      long startTimeNS) {
    CompletableFuture<LBSolrClient.Rsp> future = this.lbClient.requestAsync(lbReq);
    // Synchronize on canceled, so that we know precisely whether to add it to the responseFutureMap
    // or not.
    synchronized (canceled) {
      if (canceled.get() && !future.isDone()) {
        future.cancel(true);
        return;
      } else {
        responseFutureMap.put(srsp, future);
      }
    }
    // Add the callback explicitly after adding the future to the map, because the callback relies
    // on the map already having the future.
    future.whenComplete(
        (LBSolrClient.Rsp rsp, Throwable throwable) -> {
          if (rsp != null) {
            ssr.nl = rsp.getResponse();
            srsp.setShardAddress(rsp.getServer());
          } else if (throwable != null) {
            srsp.setException(throwable);
            if (throwable instanceof SolrException) {
              srsp.setResponseCode(((SolrException) throwable).code());
            }
          }
          ssr.elapsedTime =
              TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTimeNS, TimeUnit.NANOSECONDS);
          // Synchronize on cancelled so this code and cancelAll() cannot happen at the same time
          synchronized (canceled) {
            // We don't want to add responses after the requests have been canceled
            if (responseFutureMap.containsKey(srsp)) {
              responses.add(HttpShardHandler.this.transformResponse(sreq, srsp, shard));
            }
          }
        });
  }

  /** Subclasses could modify the request based on the shard */
  @SuppressWarnings("unused")
  protected QueryRequest createQueryRequest(
      final ShardRequest sreq, ModifiableSolrParams params, String shard) {
    // use generic request to avoid extra processing of queries
    return new QueryRequest(params);
  }

  /** Subclasses could modify the Response based on the shard */
  @SuppressWarnings("unused")
  protected ShardResponse transformResponse(
      final ShardRequest sreq, ShardResponse rsp, String shard) {
    return rsp;
  }

  @Override
  public ShardResponse takeCompletedIncludingErrors() {
    return take(false);
  }

  @Override
  public ShardResponse takeCompletedOrError() {
    return take(true);
  }

  private ShardResponse take(boolean bailOnError) {
    ShardResponse previousResponse = null;
    try {
      while (responsesPending()) {
        ShardResponse rsp = responses.take();
        if (rsp == CANCELLATION_NOTIFICATION) {
          // This is only queued in cancelAll(), so all outstanding futures have already been
          // canceled.
          responses.clear();

          // We want to return the last response we received, if possible
          if (previousResponse == null) {
            return null;
          } else {
            // If we have been canceled, return a response from this request that has an exception,
            // since that is the contract of the method.
            // Otherwise return the last response that we processed.
            return previousResponse.getShardRequest().responses.stream()
                .filter(sr -> sr.getException() != null)
                .findFirst()
                .orElse(previousResponse);
          }
        } else {
          responseFutureMap.remove(rsp);

          // add response to the response list... we do this after the take() and
          // not after the completion of "call" so we know when the last response
          // for a request was received.  Otherwise, we might return the same
          // request more than once.
          rsp.getShardRequest().responses.add(rsp);

          if (rsp.getException() != null
              && (bailOnError || disallowPartialResults(rsp.getShardRequest().params))) {
            cancelAll();
          }
        }

        if (rsp.getShardRequest().responses.size() == rsp.getShardRequest().actualShards.length) {
          return rsp;
        }
        previousResponse = rsp;
      }
    } catch (InterruptedException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
    return null;
  }

  protected boolean responsesPending() {
    return !responseFutureMap.isEmpty() || !responses.isEmpty();
  }

  @Override
  public void cancelAll() {
    // Canceled must be set to true before calling the cancellation code, to ensure that new tasks
    // are not enqueued after the outstanding requests have been canceled.
    // This code isn't perfectly threadsafe, and there can be a race-condition, but for our purposes
    // it should be fine. Failing to cancel a request, a very small percentage of the time, will
    // have very little noticeable effect. And even if the request is sent after cancellation, the
    // responses will not be recorded.
    // Queue a fake response to notify take() that it should no longer wait on responses as the
    // outstanding requests have been canceled
    synchronized (canceled) {
      boolean alreadyCanceled = canceled.getAndSet(true);
      if (!alreadyCanceled) {
        // We don't want to queue this multiple times if we are already canceled
        responses.add(CANCELLATION_NOTIFICATION);
      }
      // Cancel all outstanding requests
      for (CompletableFuture<LBSolrClient.Rsp> future : responseFutureMap.values()) {
        if (!future.isDone()) {
          future.cancel(true);
        }
      }
      responseFutureMap.clear();
    }
  }

  @Override
  public void prepDistributed(ResponseBuilder rb) {
    final SolrQueryRequest req = rb.req;
    final SolrParams params = req.getParams();
    final String shards = params.get(ShardParams.SHARDS);

    CoreDescriptor coreDescriptor = req.getCore().getCoreDescriptor();
    CloudDescriptor cloudDescriptor = req.getCloudDescriptor();
    ZkController zkController = req.getCoreContainer().getZkController();

    final ReplicaListTransformer replicaListTransformer =
        httpShardHandlerFactory.getReplicaListTransformer(req);

    AllowListUrlChecker urlChecker = req.getCoreContainer().getAllowListUrlChecker();
    if (shards != null
        && zkController == null
        && urlChecker.isEnabled()
        && !urlChecker.hasExplicitAllowList()) {
      throw new SolrException(
          SolrException.ErrorCode.FORBIDDEN,
          "solr.xml property '"
              + AllowListUrlChecker.URL_ALLOW_LIST
              + "' not configured but required (in lieu of ZkController and ClusterState) when using the '"
              + ShardParams.SHARDS
              + "' parameter. "
              + AllowListUrlChecker.SET_SOLR_DISABLE_URL_ALLOW_LIST_CLUE);
    }

    ReplicaSource replicaSource;
    if (zkController != null) {
      boolean onlyNrt = Boolean.TRUE == req.getContext().get(ONLY_NRT_REPLICAS);

      replicaSource =
          new CloudReplicaSource.Builder()
              .params(params)
              .zkStateReader(zkController.getZkStateReader())
              .allowListUrlChecker(urlChecker)
              .replicaListTransformer(replicaListTransformer)
              .collection(cloudDescriptor.getCollectionName())
              .onlyNrt(onlyNrt)
              .build();
      rb.slices = replicaSource.getSliceNames().toArray(new String[replicaSource.getSliceCount()]);

      if (canShortCircuit(rb.slices, onlyNrt, params, cloudDescriptor)) {
        rb.isDistrib = false;
        rb.shortCircuitedURL =
            ZkCoreNodeProps.getCoreUrl(zkController.getBaseUrl(), coreDescriptor.getName());
        return;
        // We shouldn't need to do anything to handle "shard.rows" since it was previously meant to
        // be an optimization?
      }

      if (!getShardsTolerantAsBool(req)) {
        for (int i = 0; i < rb.slices.length; i++) {
          if (replicaSource.getReplicasBySlice(i).isEmpty()) {
            final ReplicaSource allActiveReplicaSource =
                new CloudReplicaSource.Builder()
                    .params(params)
                    .zkStateReader(zkController.getZkStateReader())
                    .allowListUrlChecker(AllowListUrlChecker.ALLOW_ALL)
                    .replicaListTransformer(NoOpReplicaListTransformer.INSTANCE)
                    .collection(cloudDescriptor.getCollectionName())
                    .onlyNrt(false)
                    .build();
            final String adjective =
                (allActiveReplicaSource.getReplicasBySlice(i).isEmpty() ? "active" : "eligible");
            // stop the check when there are no replicas available for a shard
            // todo fix use of slices[i] which can be null if user specified urls in shards param
            throw new SolrException(
                SolrException.ErrorCode.SERVICE_UNAVAILABLE,
                "no " + adjective + " servers hosting shard: " + rb.slices[i]);
          }
        }
      }
    } else {
      replicaSource =
          new StandaloneReplicaSource.Builder()
              .allowListUrlChecker(urlChecker)
              .shards(shards)
              .build();
      rb.slices = new String[replicaSource.getSliceCount()];
    }

    rb.shards = new String[rb.slices.length];
    for (int i = 0; i < rb.slices.length; i++) {
      rb.shards[i] = createSliceShardsStr(replicaSource.getReplicasBySlice(i));
    }

    String shards_rows = params.get(ShardParams.SHARDS_ROWS);
    if (shards_rows != null) {
      rb.shards_rows = Integer.parseInt(shards_rows);
    }
    String shards_start = params.get(ShardParams.SHARDS_START);
    if (shards_start != null) {
      rb.shards_start = Integer.parseInt(shards_start);
    }
  }

  private static String createSliceShardsStr(final List<String> shardUrls) {
    return String.join("|", shardUrls);
  }

  private boolean canShortCircuit(
      String[] slices,
      boolean onlyNrtReplicas,
      SolrParams params,
      CloudDescriptor cloudDescriptor) {
    // Are we hosting the shard that this request is for, and are we active? If so, then handle it
    // ourselves and make it a non-distributed request.
    String ourSlice = cloudDescriptor.getShardId();
    String ourCollection = cloudDescriptor.getCollectionName();
    // Some requests may only be fulfilled by replicas of type Replica.Type.NRT
    if (slices.length == 1
        && slices[0] != null
        && (slices[0].equals(ourSlice)
            || slices[0].equals(
                ourCollection + "_" + ourSlice)) // handle the <collection>_<slice> format
        && cloudDescriptor.getLastPublished() == Replica.State.ACTIVE
        && (!onlyNrtReplicas || cloudDescriptor.getReplicaType() == Replica.Type.NRT)) {
      // currently just a debugging parameter to check distrib search on a single node
      boolean shortCircuit = params.getBool("shortCircuit", true);

      String targetHandler = params.get(ShardParams.SHARDS_QT);
      // if a different handler is specified, don't short-circuit
      shortCircuit = shortCircuit && targetHandler == null;

      return shortCircuit;
    }
    return false;
  }

  @Override
  public ShardHandlerFactory getShardHandlerFactory() {
    return httpShardHandlerFactory;
  }
}
