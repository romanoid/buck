/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.immutables.BuckStyleTuple;

import org.immutables.value.Value;

/**
 * The various rule key factories used by the build engine.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractRuleKeyFactories {

  /**
   * @return a {@link RuleKeyFactory} that produces {@link RuleKey}s.
   */
  public abstract RuleKeyFactoryWithDiagnostics<RuleKey> getDefaultRuleKeyFactory();

  /**
   * @return a {@link RuleKeyFactory} that produces input-based {@link RuleKey}s.
   */
  public abstract RuleKeyFactory<RuleKey> getInputBasedRuleKeyFactory();

  /**
   * @return a {@link DependencyFileRuleKeyFactory} that produces dep-file-based {@link RuleKey}s.
   */
  public abstract DependencyFileRuleKeyFactory getDepFileRuleKeyFactory();


  public static RuleKeyFactories of(
      int keySeed,
      FileHashCache fileHashCache,
      BuildRuleResolver resolver,
      long inputRuleKeyFileSizeLimit,
      RuleKeyCache<RuleKey> defaultRuleKeyFactoryCache) {
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(keySeed);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    return RuleKeyFactories.of(
        new DefaultRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder,
            defaultRuleKeyFactoryCache),
        new InputBasedRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder,
            inputRuleKeyFileSizeLimit),
        new DefaultDependencyFileRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder,
            inputRuleKeyFileSizeLimit));
  }

}
