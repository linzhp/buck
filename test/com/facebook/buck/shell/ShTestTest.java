/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.shell;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.easymock.EasyMockSupport;
import org.junit.After;
import org.junit.Test;

public class ShTestTest extends EasyMockSupport {

  @After
  public void tearDown() {
    // I don't understand why EasyMockSupport doesn't do this by default.
    verifyAll();
  }

  @Test
  public void depsAreRuntimeDeps() {
    BuildRuleResolver resolver =
        new SingleThreadedBuildRuleResolver(
            TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    BuildRule extraDep = new FakeBuildRule("//:extra_dep");
    BuildRule dep = new FakeBuildRule("//:dep");

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    ShTest shTest =
        new ShTest(
            target,
            new FakeProjectFilesystem(),
            TestBuildRuleParams.create()
                .withDeclaredDeps(ImmutableSortedSet.of(dep))
                .withExtraDeps(ImmutableSortedSet.of(extraDep)),
            /* args */ ImmutableList.of(SourcePathArg.of(FakeSourcePath.of("run_test.sh"))),
            /* env */ ImmutableMap.of(),
            /* resources */ ImmutableSortedSet.of(),
            Optional.empty(),
            /* runTestSeparately */ false,
            /* labels */ ImmutableSet.of(),
            /* type */ Optional.of("custom"),
            /* contacts */ ImmutableSet.of());

    assertThat(
        shTest.getRuntimeDeps(ruleFinder).collect(ImmutableSet.toImmutableSet()),
        containsInAnyOrder(dep.getBuildTarget(), extraDep.getBuildTarget()));
  }
}
