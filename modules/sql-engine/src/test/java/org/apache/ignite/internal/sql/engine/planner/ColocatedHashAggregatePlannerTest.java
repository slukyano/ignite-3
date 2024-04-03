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

package org.apache.ignite.internal.sql.engine.planner;

import static java.util.function.Predicate.not;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.ignite.internal.sql.engine.rel.IgniteCorrelatedNestedLoopJoin;
import org.apache.ignite.internal.sql.engine.rel.IgniteExchange;
import org.apache.ignite.internal.sql.engine.rel.IgniteLimit;
import org.apache.ignite.internal.sql.engine.rel.IgniteMergeJoin;
import org.apache.ignite.internal.sql.engine.rel.IgniteSort;
import org.apache.ignite.internal.sql.engine.rel.IgniteTableScan;
import org.apache.ignite.internal.sql.engine.rel.agg.IgniteColocatedHashAggregate;
import org.apache.ignite.internal.sql.engine.schema.IgniteIndex.Collation;
import org.apache.ignite.internal.sql.engine.trait.IgniteDistributions;
import org.apache.ignite.internal.sql.engine.trait.TraitUtils;
import org.apache.ignite.internal.util.ArrayUtils;
import org.junit.jupiter.api.Test;

/**
 * This test verifies that queries defined in {@link TestCase TestCase} can be optimized with usage of
 * colocated hash aggregates only.
 *
 * <p>See {@link AbstractAggregatePlannerTest base class} for more details.
 */
public class ColocatedHashAggregatePlannerTest extends AbstractAggregatePlannerTest {

    private final String[] disableRules = {
            "MapReduceHashAggregateConverterRule",
            "MapReduceSortAggregateConverterRule",
            "ColocatedSortAggregateConverterRule"
    };

    /**
     * Validates a plan for simple query with aggregate.
     */
    @Test
    protected void simpleAggregate() throws Exception {
        checkSimpleAggSingle(TestCase.CASE_1);
        checkSimpleAggHash(TestCase.CASE_1A);
        checkSimpleAggHash(TestCase.CASE_1B);
    }

    /**
     * Validates a plan for simple query with DISTINCT aggregates.
     *
     * @see #minMaxDistinctAggregate()
     */
    @Test
    public void distinctAggregate() throws Exception {
        checkDistinctAggSingle(TestCase.CASE_2_1);
        checkDistinctAggSingle(TestCase.CASE_2_2);

        checkDistinctAggHash(TestCase.CASE_2_1A);
        checkDistinctAggHash(TestCase.CASE_2_2A);

        checkDistinctAggHash(TestCase.CASE_2_1B);
        checkDistinctAggHash(TestCase.CASE_2_2B);

        checkColocatedDistinctAggHash(TestCase.CASE_2_1C);
        checkColocatedDistinctAggHash(TestCase.CASE_2_2C);

        checkColocatedDistinctAggHash(TestCase.CASE_2_1D);
        checkColocatedDistinctAggHash(TestCase.CASE_2_2D);
    }

    /**
     * Validates a plan for a query with min/max distinct aggregate.
     *
     * <p>NB: DISTINCT make no sense for MIN/MAX, thus expected plan is the same as in {@link #simpleAggregate()} ()}
     */
    @Test
    public void minMaxDistinctAggregate() throws Exception {
        checkSimpleAggSingle(TestCase.CASE_3_1);
        checkSimpleAggSingle(TestCase.CASE_3_2);

        checkSimpleAggHash(TestCase.CASE_3_1A);
        checkSimpleAggHash(TestCase.CASE_3_2A);

        checkSimpleAggHash(TestCase.CASE_3_1B);
        checkSimpleAggHash(TestCase.CASE_3_2B);

        checkSimpleAggHash(TestCase.CASE_3_1C);
        checkSimpleAggHash(TestCase.CASE_3_2C);

        checkSimpleAggHash(TestCase.CASE_3_1D);
        checkSimpleAggHash(TestCase.CASE_3_2D);
    }

    /**
     * Validates a plan for a query with aggregate and groups.
     */
    @Test
    public void simpleAggregateWithGroupBy() throws Exception {
        checkSimpleAggWithGroupBySingle(TestCase.CASE_5);
        checkSimpleAggWithGroupBySingle(TestCase.CASE_6);

        checkSimpleAggWithGroupByHash(TestCase.CASE_5A);
        checkSimpleAggWithGroupByHash(TestCase.CASE_6A);

        checkSimpleAggWithGroupByHash(TestCase.CASE_5B);
        checkSimpleAggWithGroupByHash(TestCase.CASE_6B);

        checkSimpleAggWithColocatedGroupBy(TestCase.CASE_5C);
        checkSimpleAggWithColocatedGroupBy(TestCase.CASE_6C);

        checkSimpleAggWithColocatedGroupBy(TestCase.CASE_5D);
    }

    /**
     * Validates a plan for a query with DISTINCT aggregates and groups.
     *
     * @see #minMaxDistinctAggregateWithGroupBy()
     */
    @Test
    public void distinctAggregateWithGroups() throws Exception {
        checkDistinctAggWithGroupBySingle(TestCase.CASE_7_1);
        checkDistinctAggWithGroupBySingle(TestCase.CASE_7_2);
        checkDistinctAggWithGroupBySingle(TestCase.CASE_7_3);

        checkDistinctAggWithGroupByHash(TestCase.CASE_7_1A);
        checkDistinctAggWithGroupByHash(TestCase.CASE_7_2A);
        checkDistinctAggWithGroupByHash(TestCase.CASE_7_3A);

        checkDistinctAggWithGroupByHash(TestCase.CASE_7_1B);
        checkDistinctAggWithGroupByHash(TestCase.CASE_7_2B);
        checkDistinctAggWithGroupByHash(TestCase.CASE_7_3B);

        checkDistinctAggWithColocatedGroupByHash(TestCase.CASE_7_1C);
        checkDistinctAggWithColocatedGroupByHash(TestCase.CASE_7_2C);
        checkDistinctAggWithColocatedGroupByHash(TestCase.CASE_7_3C);

        checkDistinctAggWithColocatedGroupByHash(TestCase.CASE_7_1D);
        checkDistinctAggWithColocatedGroupByHash(TestCase.CASE_7_2D);
        checkDistinctAggWithColocatedGroupByHash(TestCase.CASE_7_3D);
    }

    /**
     * Validates a plan for a query with min/max distinct aggregates and groups.
     *
     * <p>NB: DISTINCT make no sense for MIN/MAX, thus expected plan is the same as in {@link #simpleAggregateWithGroupBy()}
     */
    @Test
    public void minMaxDistinctAggregateWithGroupBy() throws Exception {
        checkSimpleAggWithGroupBySingle(TestCase.CASE_8_1);
        checkSimpleAggWithGroupBySingle(TestCase.CASE_8_2);

        checkSimpleAggWithGroupByHash(TestCase.CASE_8_1A);
        checkSimpleAggWithGroupByHash(TestCase.CASE_8_2A);

        checkSimpleAggWithGroupByHash(TestCase.CASE_8_1B);
        checkSimpleAggWithGroupByHash(TestCase.CASE_8_2B);

        checkSimpleAggWithColocatedGroupBy(TestCase.CASE_8_1C);
        checkSimpleAggWithColocatedGroupBy(TestCase.CASE_8_2C);

        checkSimpleAggWithColocatedGroupBy(TestCase.CASE_8_1D);
        checkSimpleAggWithColocatedGroupBy(TestCase.CASE_8_2D);
    }

    /**
     * Validates a plan uses an index for a query with aggregate if grouped by index prefix.
     *
     * <p>NB: GROUP BY columns order permutation shouldn't affect the plan.
     */
    @Test
    public void aggregateWithGroupByIndexPrefixColumns() throws Exception {
        checkAggWithGroupByIndexColumnsSingle(TestCase.CASE_9);
        checkAggWithGroupByIndexColumnsSingle(TestCase.CASE_10);
        checkAggWithGroupByIndexColumnsSingle(TestCase.CASE_11);

        checkAggWithGroupByIndexColumnsHash(TestCase.CASE_9A);
        checkAggWithGroupByIndexColumnsHash(TestCase.CASE_10A);
        checkAggWithGroupByIndexColumnsHash(TestCase.CASE_11A);

        checkAggWithGroupByIndexColumnsHash(TestCase.CASE_9B);
        checkAggWithGroupByIndexColumnsHash(TestCase.CASE_10B);
        checkAggWithGroupByIndexColumnsHash(TestCase.CASE_11B);

        checkAggWithColocatedGroupByIndexColumnsHash(TestCase.CASE_9C);
        checkAggWithColocatedGroupByIndexColumnsHash(TestCase.CASE_10C);
        checkAggWithColocatedGroupByIndexColumnsHash(TestCase.CASE_11C);

        checkAggWithColocatedGroupByIndexColumnsHash(TestCase.CASE_9D);
    }

    /**
     * Validates a plan for a query with DISTINCT and without aggregation function.
     */
    @Test
    public void distinctWithoutAggregate() throws Exception {
        checkGroupWithNoAggregateSingle(TestCase.CASE_12);
        checkGroupWithNoAggregateHash(TestCase.CASE_12A);
        checkGroupWithNoAggregateHash(TestCase.CASE_12B);

        checkColocatedGroupWithNoAggregateHash(TestCase.CASE_12C);
        checkColocatedGroupWithNoAggregateHash(TestCase.CASE_12D);

        checkGroupWithNoAggregateSingle(TestCase.CASE_13);
        checkGroupWithNoAggregateHash(TestCase.CASE_13A);
        checkGroupWithNoAggregateHash(TestCase.CASE_13B);

        checkColocatedGroupWithNoAggregateHash(TestCase.CASE_13C);
        checkColocatedGroupWithNoAggregateHash(TestCase.CASE_13D);
    }

    /**
     * Validates a plan for a query which WHERE clause contains a sub-query with aggregate.
     */
    @Test
    public void subqueryWithAggregateInWhereClause() throws Exception {
        checkSimpleAggSingle(TestCase.CASE_14);
        checkSimpleAggHash(TestCase.CASE_14A);
        checkSimpleAggHash(TestCase.CASE_14B);
    }

    /**
     * Validates a plan for a query with DISTINCT aggregate in WHERE clause.
     */
    @Test
    public void distinctAggregateInWhereClause() throws Exception {
        checkGroupWithNoAggregateSingle(TestCase.CASE_15);
        checkGroupWithNoAggregateHash(TestCase.CASE_15A);
        checkGroupWithNoAggregateHash(TestCase.CASE_15B);
    }

    /**
     * Validates a plan with merge-sort utilizes index if collation fits.
     */
    @Test
    public void noSortAppendingWithCorrectCollation() throws Exception {
        String[] additionalRulesToDisable = {"NestedLoopJoinConverter", "CorrelatedNestedLoopJoin", "CorrelateToNestedLoopRule"};

        assertPlan(TestCase.CASE_16,
                nodeOrAnyChild(isInstanceOf(IgniteSort.class)
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(input(isTableScan("TEST")))
                        ))
                ),
                ArrayUtils.concat(disableRules, additionalRulesToDisable)
        );

        assertPlan(TestCase.CASE_16A,
                nodeOrAnyChild(isInstanceOf(IgniteSort.class)
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(input(isInstanceOf(IgniteExchange.class)
                                        .and(input(isTableScan("TEST")))
                                ))
                        ))
                ),
                ArrayUtils.concat(disableRules, additionalRulesToDisable)
        );
        assertPlan(TestCase.CASE_16B,
                nodeOrAnyChild(isInstanceOf(IgniteSort.class)
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(input(isInstanceOf(IgniteExchange.class)
                                        .and(input(isTableScan("TEST")))
                                ))
                        ))
                ),
                ArrayUtils.concat(disableRules, additionalRulesToDisable)
        );
    }

    /**
     * Validates a plan for a sub-query with order and limit.
     */
    @Test
    public void emptyCollationPassThroughLimit() throws Exception {
        assertPlan(TestCase.CASE_17,
                hasChildThat(isInstanceOf(IgniteCorrelatedNestedLoopJoin.class)
                        .and(input(1, isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(input(isInstanceOf(IgniteLimit.class)
                                        .and(input(isInstanceOf(IgniteSort.class)
                                                .and(input(isTableScan("TEST")))
                                        ))
                                ))
                        ))
                ),
                disableRules
        );

        assertPlan(TestCase.CASE_17A,
                hasChildThat(isInstanceOf(IgniteCorrelatedNestedLoopJoin.class)
                        .and(input(1, isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(input(isInstanceOf(IgniteLimit.class)
                                        .and(input(isInstanceOf(IgniteExchange.class)
                                                .and(input(isInstanceOf(IgniteSort.class)
                                                        .and(input(isTableScan("TEST")))
                                                ))
                                        ))
                                ))
                        ))
                ),
                disableRules
        );
        assertPlan(TestCase.CASE_17B,
                hasChildThat(isInstanceOf(IgniteCorrelatedNestedLoopJoin.class)
                        .and(input(1, isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(input(isInstanceOf(IgniteLimit.class)
                                        .and(input(isInstanceOf(IgniteExchange.class)
                                                .and(input(isInstanceOf(IgniteSort.class)
                                                        .and(input(isTableScan("TEST")))
                                                ))
                                        ))
                                ))
                        ))
                ),
                disableRules
        );
    }

    /**
     * Validates a plan for a query with aggregate and with groups and sorting by the same column set.
     */
    @Test
    public void groupsWithOrderByGroupColumns() throws Exception {
        checkGroupsWithOrderByGroupColumnsSingle(TestCase.CASE_18_1, TraitUtils.createCollation(List.of(0, 1)));
        checkGroupsWithOrderByGroupColumnsSingle(TestCase.CASE_18_2, TraitUtils.createCollation(List.of(1, 0)));
        checkGroupsWithOrderByGroupColumnsSingle(TestCase.CASE_18_3, TraitUtils.createCollation(List.of(1, 0)));

        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_18_1A, TraitUtils.createCollation(List.of(0, 1)));
        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_18_2A, TraitUtils.createCollation(List.of(1, 0)));
        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_18_3A, TraitUtils.createCollation(List.of(1, 0)));

        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_18_1B, TraitUtils.createCollation(List.of(0, 1)));
        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_18_2B, TraitUtils.createCollation(List.of(1, 0)));
        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_18_3B, TraitUtils.createCollation(List.of(1, 0)));
    }

    /**
     * Validates a plan for a query with aggregate and with sorting by subset of group columns.
     */
    @Test
    public void aggregateWithOrderByGroupColumns() throws Exception {
        checkGroupsWithOrderByGroupColumnsSingle(TestCase.CASE_19_1, TraitUtils.createCollation(List.of(0)));
        checkGroupsWithOrderByGroupColumnsSingle(TestCase.CASE_19_2, TraitUtils.createCollation(List.of(1)));
        checkGroupsWithOrderByGroupColumnsSingle(TestCase.CASE_20, TraitUtils.createCollation(List.of(0, 1, 2)));

        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_19_1A, TraitUtils.createCollation(List.of(0)));
        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_19_2A, TraitUtils.createCollation(List.of(1)));
        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_20A, TraitUtils.createCollation(List.of(0, 1, 2)));

        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_19_1B, TraitUtils.createCollation(List.of(0)));
        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_19_2B, TraitUtils.createCollation(List.of(1)));
        checkGroupsWithOrderByGroupColumnsHash(TestCase.CASE_20B, TraitUtils.createCollation(List.of(0, 1, 2)));
    }

    /**
     * Validates a plan for a query with aggregate and groups, and EXPAND_DISTINCT_AGG hint.
     */
    @Test
    public void expandDistinctAggregates() throws Exception {
        Predicate<? extends RelNode> subtreePredicate = nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                // Check the second aggregation step contains accumulators.
                // Plan must not contain distinct accumulators.
                .and(hasAggregate())
                .and(not(hasDistinctAggregate()))
                // Check the first aggregation step is SELECT DISTINCT (doesn't contain any accumulators)
                .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(not(hasAggregate()))
                        .and(hasGroups())
                ))
        );

        assertPlan(TestCase.CASE_21, nodeOrAnyChild(isInstanceOf(IgniteMergeJoin.class)
                .and(input(0, subtreePredicate))
                .and(input(1, subtreePredicate))
        ), disableRules);

        subtreePredicate = nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                // Check the second aggregation step contains accumulators.
                // Plan must not contain distinct accumulators.
                .and(hasAggregate())
                .and(not(hasDistinctAggregate()))
                // Check the first aggregation step is SELECT DISTINCT (doesn't contain any accumulators)
                .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(not(hasAggregate()))
                        .and(hasGroups())
                ))
        );

        assertPlan(TestCase.CASE_21A, nodeOrAnyChild(isInstanceOf(IgniteMergeJoin.class)
                .and(input(0, subtreePredicate))
                .and(input(1, subtreePredicate))
        ), disableRules);
        assertPlan(TestCase.CASE_21B, nodeOrAnyChild(isInstanceOf(IgniteMergeJoin.class)
                .and(input(0, subtreePredicate))
                .and(input(1, subtreePredicate))
        ), disableRules);
    }

    /**
     * Validates that single phase COUNT aggregate is used.
     */
    @Test
    public void testCountAgg() throws Exception {
        Predicate<AggregateCall> countMap = (a) ->
                Objects.equals(a.getAggregation().getName(), "COUNT") && a.getArgList().equals(List.of(1));

        Predicate<IgniteColocatedHashAggregate> nonColocatedGroupBy = isInstanceOf(IgniteColocatedHashAggregate.class)
                .and(in -> hasAggregates(countMap).test(in.getAggCallList()))
                .and(input(isInstanceOf(IgniteExchange.class)
                        .and(hasDistribution(IgniteDistributions.single()))));

        assertPlan(TestCase.CASE_22, nonColocatedGroupBy, disableRules);
        assertPlan(TestCase.CASE_22A, nonColocatedGroupBy, disableRules);

        Predicate<IgniteExchange> colocatedGroupBy = isInstanceOf(IgniteExchange.class)
                .and(hasDistribution(IgniteDistributions.single()))
                .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(in -> hasAggregates(countMap).test(in.getAggCallList()))));

        assertPlan(TestCase.CASE_22B, colocatedGroupBy, disableRules);
        assertPlan(TestCase.CASE_22C, colocatedGroupBy, disableRules);
    }

    /**
     * Validates that single phase AVG aggregate is used.
     */
    @Test
    public void testAvgAgg() throws Exception {
        Predicate<AggregateCall> countMap = (a) -> Objects.equals(a.getAggregation().getName(), "AVG") && a.getArgList().equals(List.of(1));

        Predicate<IgniteColocatedHashAggregate> nonColocatedGroupBy = isInstanceOf(IgniteColocatedHashAggregate.class)
                .and(in -> hasAggregates(countMap).test(in.getAggCallList()))
                .and(input(isInstanceOf(IgniteExchange.class)
                        .and(hasDistribution(IgniteDistributions.single()))));

        assertPlan(TestCase.CASE_23, nonColocatedGroupBy, disableRules);
        assertPlan(TestCase.CASE_23A, nonColocatedGroupBy, disableRules);

        Predicate<IgniteExchange> colocatedGroupBy = isInstanceOf(IgniteExchange.class)
                .and(hasDistribution(IgniteDistributions.single()))
                .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(in -> hasAggregates(countMap).test(in.getAggCallList()))));

        assertPlan(TestCase.CASE_23B, colocatedGroupBy, disableRules);
        assertPlan(TestCase.CASE_23C, colocatedGroupBy, disableRules);

    }

    /**
     * Validates a plan for a query with two aggregates: one w/o DISTINCT and one with DISTINCT: single distribution.
     */
    @Test
    public void countDistinctGroupSetSingle() throws Exception {
        assertPlan(TestCase.CASE_24_1, isInstanceOf(IgniteColocatedHashAggregate.class)
                .and(hasNoGroupSets(IgniteColocatedHashAggregate::getGroupSets))
                .and(input(isInstanceOf(IgniteTableScan.class)
                )), disableRules);
    }

    /**
     * Validates a plan for a query with two aggregates: one w/o DISTINCT and one with DISTINCT: hash distribution.
     */
    @Test
    public void countDistinctGroupSetHash() throws Exception {
        checkCountDistinctHash(TestCase.CASE_24_1A);
        checkCountDistinctHash(TestCase.CASE_24_1B);
        checkCountDistinctHash(TestCase.CASE_24_1D);

        Predicate<RelNode> colocated2 = nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                .and(hasNoGroupSets(IgniteColocatedHashAggregate::getGroupSets))
                .and(input(isInstanceOf(IgniteExchange.class)
                        .and(hasDistribution(IgniteDistributions.single()))
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(hasGroupSets(IgniteColocatedHashAggregate::getGroupSets, 1))
                                .and(input(isInstanceOf(IgniteTableScan.class))))
                        ))
                ));

        assertPlan(TestCase.CASE_24_1C, colocated2, disableRules);
        assertPlan(TestCase.CASE_24_1E, colocated2, disableRules);
    }


    /**
     * Validates a plan for a query with aggregate and with groups and sorting by the same column set in descending order.
     */
    @Test
    public void groupsWithOrderByGroupColumnDescending() throws Exception {
        checkDerivedCollationWithOrderByGroupColumnSingle(TestCase.CASE_25);

        checkDerivedCollationWithOrderByGroupColumnHash(TestCase.CASE_25A);
    }

    /**
     * Validates a plan for a query with aggregate and with sorting in descending order by a subset of grouping column.
     */
    @Test
    public void groupsWithOrderBySubsetOfGroupColumnDescending() throws Exception {
        checkDerivedCollationWithOrderBySubsetOfGroupColumnsSingle(TestCase.CASE_26);

        checkDerivedCollationWithOrderBySubsetOfGroupColumnsHash(TestCase.CASE_26A);
    }

    private void checkSimpleAggSingle(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasAggregate())
                        .and(input(isTableScan("TEST")))
                ),
                disableRules
        );
    }

    private void checkSimpleAggHash(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasAggregate())
                        .and(input(isInstanceOf(IgniteExchange.class)
                                .and(input(isTableScan("TEST")))
                        ))
                ),
                disableRules
        );
    }

    private void checkSimpleAggWithGroupBySingle(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasAggregate())
                        .and(hasGroups())
                        .and(input(isTableScan("TEST")))
                ),
                disableRules
        );
    }

    private void checkSimpleAggWithGroupByHash(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasAggregate())
                        .and(hasGroups())
                        .and(input(isInstanceOf(IgniteExchange.class)
                                .and(input(isTableScan("TEST")))
                        ))),
                disableRules
        );
    }

    private void checkSimpleAggWithColocatedGroupBy(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteExchange.class)
                        .and(nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(hasAggregate())
                                .and(hasGroups())
                                .and(input(isTableScan("TEST")))
                        ))),
                disableRules
        );
    }

    private void checkDistinctAggWithColocatedGroupByHash(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteExchange.class)
                        .and(nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(hasAggregate())
                                .and(hasGroups())
                                .and(hasDistinctAggregate())
                                .and(input(isTableScan("TEST")))
                        ))),
                disableRules
        );
    }

    private void checkDistinctAggSingle(TestCase testCase) throws Exception {
        assertPlan(testCase,
                isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasAggregate())
                        .and(not(hasDistinctAggregate()))
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(hasGroups())
                                .and(input(isTableScan("TEST")))
                        )),
                disableRules
        );
    }

    private void checkDistinctAggHash(TestCase testCase) throws Exception {
        assertPlan(testCase,
                isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasAggregate())
                        .and(not(hasDistinctAggregate()))
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(hasGroups())
                                .and(input(isInstanceOf(IgniteExchange.class)
                                        .and(input(isTableScan("TEST")))
                                ))
                        )),
                disableRules
        );
    }

    private void checkColocatedDistinctAggHash(TestCase testCase) throws Exception {
        assertPlan(testCase,
                isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasAggregate())
                        .and(not(hasDistinctAggregate()))
                        .and(input(isInstanceOf(IgniteExchange.class)
                                .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                        .and(hasGroups())
                                        .and(input(isTableScan("TEST")))
                                ))
                        )),
                disableRules
        );
    }

    private void checkDistinctAggWithGroupBySingle(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasDistinctAggregate())
                        .and(hasGroups())
                        .and(input(isTableScan("TEST")))
                ),
                disableRules
        );
    }

    private void checkDistinctAggWithGroupByHash(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasDistinctAggregate())
                        .and(hasGroups())
                        .and(input(isInstanceOf(IgniteExchange.class)
                                .and(input(isTableScan("TEST")))
                        ))
                ),
                disableRules
        );
    }

    private void checkAggWithGroupByIndexColumnsSingle(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasAggregate())
                        .and(input(isTableScan("TEST")))
                ),
                disableRules
        );
    }

    private void checkAggWithGroupByIndexColumnsHash(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(hasAggregate())
                        .and(input(isInstanceOf(IgniteExchange.class)
                                .and(input(isTableScan("TEST")))
                        ))
                ),
                disableRules
        );
    }

    private void checkAggWithColocatedGroupByIndexColumnsHash(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteExchange.class)
                        .and(nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(hasAggregate())
                                .and(input(isTableScan("TEST")))
                        ))
                ),
                disableRules
        );
    }

    private void checkGroupWithNoAggregateSingle(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(not(hasAggregate()))
                        .and(hasGroups())
                        .and(input(isTableScan("TEST")))
                ),
                disableRules
        );
    }

    private void checkGroupWithNoAggregateHash(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                        .and(not(hasAggregate()))
                        .and(hasGroups())
                        .and(input(isInstanceOf(IgniteExchange.class)
                                .and(input(isTableScan("TEST")))
                        ))
                ),
                disableRules
        );
    }

    private void checkColocatedGroupWithNoAggregateHash(TestCase testCase) throws Exception {
        assertPlan(testCase,
                nodeOrAnyChild(isInstanceOf(IgniteExchange.class)
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(not(hasAggregate()))
                                .and(hasGroups())
                                .and(input(isTableScan("TEST")))
                        ))
                ),
                disableRules
        );
    }

    private void checkGroupsWithOrderByGroupColumnsSingle(TestCase testCase, RelCollation collation) throws Exception {
        assertPlan(testCase,
                isInstanceOf(IgniteSort.class)
                        .and(s -> s.collation().equals(collation))
                        .and(nodeOrAnyChild(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(input(isTableScan("TEST")))
                        )),
                disableRules
        );
    }

    private void checkGroupsWithOrderByGroupColumnsHash(TestCase testCase, RelCollation collation) throws Exception {
        assertPlan(testCase,
                isInstanceOf(IgniteSort.class)
                        .and(s -> s.collation().equals(collation))
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(input(isInstanceOf(IgniteExchange.class)
                                        .and(input(isTableScan("TEST")))
                                ))
                        )),
                disableRules
        );
    }

    private void checkCountDistinctHash(TestCase testCase) throws Exception {
        assertPlan(testCase, isInstanceOf(IgniteColocatedHashAggregate.class)
                .and(hasNoGroupSets(IgniteColocatedHashAggregate::getGroupSets))
                .and(input(isInstanceOf(IgniteExchange.class)
                        .and(hasDistribution(IgniteDistributions.single()))
                        .and(input(isInstanceOf(IgniteTableScan.class))))
                ), disableRules);
    }

    private void checkDerivedCollationWithOrderByGroupColumnSingle(TestCase testCase) throws Exception {
        RelCollation requiredCollation = RelCollations.of(TraitUtils.createFieldCollation(0, Collation.DESC_NULLS_FIRST));

        assertPlan(testCase,
                isInstanceOf(IgniteSort.class)
                        .and(hasCollation(requiredCollation))
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class))),
                disableRules);
    }

    private void checkDerivedCollationWithOrderByGroupColumnHash(TestCase testCase) throws Exception {
        RelCollation requiredCollation = RelCollations.of(TraitUtils.createFieldCollation(0, Collation.DESC_NULLS_FIRST));

        assertPlan(testCase,
                isInstanceOf(IgniteSort.class)
                        .and(hasCollation(requiredCollation))
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(hasAggregate())
                        )),
                disableRules);
    }

    private void checkDerivedCollationWithOrderBySubsetOfGroupColumnsSingle(TestCase testCase) throws Exception {
        RelCollation outputCollation = RelCollations.of(
                TraitUtils.createFieldCollation(1, Collation.DESC_NULLS_FIRST)
        );

        assertPlan(testCase,
                isInstanceOf(IgniteSort.class)
                        .and(hasCollation(outputCollation))
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class))),
                disableRules);
    }

    private void checkDerivedCollationWithOrderBySubsetOfGroupColumnsHash(TestCase testCase) throws Exception {
        RelCollation outputCollation = RelCollations.of(
                TraitUtils.createFieldCollation(1, Collation.DESC_NULLS_FIRST)
        );

        assertPlan(testCase,
                isInstanceOf(IgniteSort.class)
                        .and(hasCollation(outputCollation))
                        .and(input(isInstanceOf(IgniteColocatedHashAggregate.class)
                                .and(input(isInstanceOf(IgniteExchange.class)
                                        .and(hasDistribution(IgniteDistributions.single()))
                                ))
                        )),
                disableRules);
    }
}
