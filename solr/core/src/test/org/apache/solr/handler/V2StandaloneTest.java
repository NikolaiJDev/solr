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

package org.apache.solr.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.io.file.PathUtils;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.V2Request;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.embedded.JettyConfig;
import org.apache.solr.embedded.JettySolrRunner;
import org.junit.Test;

public class V2StandaloneTest extends SolrTestCaseJ4 {

  @Test
  public void testWelcomeMessage() throws Exception {
    Path solrHomeTmp = createTempDir();
    PathUtils.copyDirectory(
        TEST_HOME().resolve("configsets/minimal/conf"), solrHomeTmp.resolve("conf"));
    Files.copy(TEST_HOME().resolve("solr.xml"), solrHomeTmp.resolve("solr.xml"));

    JettySolrRunner jetty =
        new JettySolrRunner(solrHomeTmp.toString(), JettyConfig.builder().build());
    jetty.start();

    try (SolrClient client = getHttpSolrClient(buildUrl(jetty.getLocalPort()))) {
      NamedList<?> res = client.request(new V2Request.Builder("/").build());
      NamedList<?> header = (NamedList<?>) res.get("responseHeader");
      assertEquals(0, header.get("status"));

      res = client.request(new V2Request.Builder("/_introspect").build());
      header = (NamedList<?>) res.get("responseHeader");
      assertEquals(0, header.get("status"));
    }

    jetty.stop();
  }
}
