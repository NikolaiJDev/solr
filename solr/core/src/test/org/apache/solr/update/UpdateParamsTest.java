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
package org.apache.solr.update;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.UpdateRequestHandler;
import org.apache.solr.request.SolrQueryRequestBase;
import org.apache.solr.response.SolrQueryResponse;
import org.junit.BeforeClass;

public class UpdateParamsTest extends SolrTestCaseJ4 {

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml");
  }

  /** Tests that only update.chain and not update.processor works (SOLR-2105) */
  public void testUpdateProcessorParamDeprecationRemoved() {
    SolrCore core = h.getCore();

    UpdateRequestHandler handler = new UpdateRequestHandler();
    handler.init(null);

    var params = new ModifiableSolrParams().set("update.processor", "nonexistent");

    // Add a single document
    SolrQueryResponse rsp = new SolrQueryResponse();
    SolrQueryRequestBase req = new SolrQueryRequestBase(core, params) {};

    // First check that the old param behaves as it should
    try {
      handler.handleRequestBody(req, rsp);
      assertTrue("Old param update.processor should not have any effect anymore", true);
    } catch (Exception e) {
      assertNotEquals(
          "Got wrong exception while testing update.chain",
          "unknown UpdateRequestProcessorChain: nonexistent",
          e.getMessage());
    }

    // Then check that the new param behaves correctly
    params.remove("update.processor");
    params.set(UpdateParams.UPDATE_CHAIN, "nonexistent");
    req.setParams(params);
    try {
      handler.handleRequestBody(req, rsp);
      fail("Faulty update.chain parameter not causing an error - i.e. it is not detected");
    } catch (Exception e) {
      assertEquals(
          "Got wrong exception while testing update.chain",
          e.getMessage(),
          "unknown UpdateRequestProcessorChain: nonexistent");
    }
  }
}
