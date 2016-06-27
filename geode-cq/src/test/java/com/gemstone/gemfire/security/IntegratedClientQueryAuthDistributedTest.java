/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gemstone.gemfire.security;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.client.Pool;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.query.CqAttributes;
import com.gemstone.gemfire.cache.query.CqAttributesFactory;
import com.gemstone.gemfire.cache.query.CqQuery;
import com.gemstone.gemfire.cache.query.QueryService;
import com.gemstone.gemfire.test.junit.categories.DistributedTest;

@Category(DistributedTest.class)
public class IntegratedClientQueryAuthDistributedTest extends AbstractIntegratedClientAuthDistributedTest {

  @Test
  public void testQuery(){
    client1.invoke(()-> {
      Cache cache = SecurityTestUtils.createCacheClient("stranger", "1234567", serverPort, SecurityTestUtils.NO_EXCEPTION);
      final Region region = cache.getRegion(SecurityTestUtils.REGION_NAME);

      String query = "select * from /AuthRegion";
      assertNotAuthorized(()->region.query(query), "DATA:READ:AuthRegion");

      Pool pool = PoolManager.find(region);
      assertNotAuthorized(()->pool.getQueryService().newQuery(query).execute(), "DATA:READ:AuthRegion");
    });
  }

  @Test
  public void testCQ(){
    String query = "select * from /AuthRegion";
    client1.invoke(()-> {
      Cache cache = SecurityTestUtils.createCacheClient("stranger", "1234567", serverPort, SecurityTestUtils.NO_EXCEPTION);
      Region region = cache.getRegion(SecurityTestUtils.REGION_NAME);
      Pool pool = PoolManager.find(region);
      QueryService qs = pool.getQueryService();

      CqAttributes cqa = new CqAttributesFactory().create();

      // Create the CqQuery
      CqQuery cq = qs.newCq("CQ1", query, cqa);

      assertNotAuthorized(()->cq.executeWithInitialResults(), "DATA:READ:AuthRegion");
      assertNotAuthorized(()->cq.execute(), "DATA:READ:AuthRegion");

      assertNotAuthorized(()->cq.close(), "DATA:MANAGE");
    });

    client2.invoke(()-> {
      Cache cache = SecurityTestUtils.createCacheClient("authRegionReader", "1234567", serverPort, SecurityTestUtils.NO_EXCEPTION);
      Region region = cache.getRegion(SecurityTestUtils.REGION_NAME);
      Pool pool = PoolManager.find(region);
      QueryService qs = pool.getQueryService();

      CqAttributes cqa = new CqAttributesFactory().create();
      // Create the CqQuery
      CqQuery cq = qs.newCq("CQ1", query, cqa);
      cq.execute();

      assertNotAuthorized(()->cq.stop(), "DATA:MANAGE");
      assertNotAuthorized(()->qs.getAllDurableCqsFromServer(), "CLUSTER:READ");
    });

    client3.invoke(()-> {
      Cache cache = SecurityTestUtils.createCacheClient("super-user", "1234567", serverPort, SecurityTestUtils.NO_EXCEPTION);
      Region region = cache.getRegion(SecurityTestUtils.REGION_NAME);
      Pool pool = PoolManager.find(region);
      QueryService qs = pool.getQueryService();

      CqAttributes cqa = new CqAttributesFactory().create();

      // Create the CqQuery
      CqQuery cq = qs.newCq("CQ1", query, cqa);
      cq.execute();

      cq.stop();
    });
  }

}