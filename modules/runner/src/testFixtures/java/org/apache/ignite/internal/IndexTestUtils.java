/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal;

import static org.apache.ignite.internal.TestWrappers.unwrapIgniteImpl;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.waitForCondition;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.ignite.Ignite;
import org.apache.ignite.internal.app.IgniteImpl;
import org.apache.ignite.internal.catalog.CatalogManager;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.sql.SqlCommon;

/**
 * Utils to help to work with indexes in integration tests.
 */
@SuppressWarnings("TestOnlyProblems")
public class IndexTestUtils {
    /**
     * Waits for an index to appear in the Catalog of the given Ignite node.
     *
     * @param indexName Name of the index.
     * @param ignite Ignite node.
     * @throws InterruptedException If interrupted.
     */
    public static void waitForIndexToAppearInAnyState(String indexName, Ignite ignite) throws InterruptedException {
        IgniteImpl igniteImpl = unwrapIgniteImpl(ignite);
        HybridClock clock = igniteImpl.clock();
        CatalogManager catalogManager = igniteImpl.catalogManager();

        assertTrue(waitForCondition(
                () -> catalogManager.activeCatalog(clock.nowLong()).aliveIndex(SqlCommon.DEFAULT_SCHEMA_NAME, indexName) != null,
                10_000
        ));
    }
}
