/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.classes.ClasspathTraversal;
import com.facebook.buck.jvm.java.classes.DefaultClasspathTraverser;
import com.facebook.buck.jvm.java.classes.FileLike;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.zip.DeterministicZipBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Split a jar generated by compiling uber-R.java files into subset jars. */
public class SplitUberRDotJavaJar extends ModernBuildRule<SplitUberRDotJavaJar>
    implements Buildable {

  private static final ImmutableSet<String> RESOURCE_TYPES =
      ImmutableSet.of("id", "string", "_primarydex", "_other");

  private static final Pattern RESOURCE_TYPE_PATTERN = Pattern.compile("^.*/R[$](\\w+)\\.class$");

  @AddToRuleKey private final SourcePath uberRDotJavaJar;
  @AddToRuleKey private final DexSplitMode dexSplitMode;

  @AddToRuleKey private final ImmutableMap<String, OutputPath> outputs;

  public SplitUberRDotJavaJar(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder finder,
      SourcePath uberRDotJavaJar,
      DexSplitMode dexSplitMode) {
    super(buildTarget, projectFilesystem, finder, SplitUberRDotJavaJar.class);
    this.uberRDotJavaJar = uberRDotJavaJar;
    this.dexSplitMode = dexSplitMode;

    ImmutableMap.Builder<String, OutputPath> builder =
        ImmutableMap.builderWithExpectedSize(RESOURCE_TYPES.size());
    for (String rtype : RESOURCE_TYPES) {
      builder.put(rtype, new OutputPath(rtype + ".jar"));
    }
    outputs = builder.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    return ImmutableList.of(
        new SplitUberRDotJavaJarStep(buildContext, filesystem, outputPathResolver));
  }

  /**
   * @return A map from resource type (or special sentinel name) to a source path for jar file that
   *     contains those classes.
   */
  public ImmutableMap<String, BuildTargetSourcePath> getOutputJars() {
    ImmutableMap.Builder<String, BuildTargetSourcePath> builder = ImmutableMap.builder();
    for (Entry<String, OutputPath> entry : outputs.entrySet()) {
      builder.put(entry.getKey(), getSourcePath(entry.getValue()));
    }
    return builder.build();
  }

  private class SplitUberRDotJavaJarStep implements Step {
    private final BuildContext buildContext;
    private final ProjectFilesystem filesystem;
    private final OutputPathResolver outputPathResolver;

    public SplitUberRDotJavaJarStep(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver) {
      this.buildContext = buildContext;
      this.filesystem = filesystem;
      this.outputPathResolver = outputPathResolver;
    }

    @Override
    public StepExecutionResult execute(ExecutionContext context)
        throws IOException, InterruptedException {
      ClassNameFilter primaryDexFilter =
          ClassNameFilter.fromConfiguration(dexSplitMode.getPrimaryDexPatterns());

      try (Closer closer = Closer.create()) {

        ImmutableMap.Builder<String, DeterministicZipBuilder> zipMapBuilder =
            ImmutableMap.builder();
        for (Entry<String, OutputPath> entry : outputs.entrySet()) {
          String rtype = entry.getKey();
          Path zipPath =
              getProjectFilesystem().resolve(outputPathResolver.resolvePath(entry.getValue()));
          DeterministicZipBuilder zip = new DeterministicZipBuilder(zipPath);
          closer.register(zip);
          zipMapBuilder.put(rtype, zip);
        }
        ImmutableMap<String, DeterministicZipBuilder> outputZips = zipMapBuilder.build();

        new DefaultClasspathTraverser()
            .traverse(
                new ClasspathTraversal(
                    ImmutableList.of(
                        buildContext.getSourcePathResolver().getAbsolutePath(uberRDotJavaJar)),
                    filesystem) {
                  @Override
                  public void visit(FileLike fileLike) throws IOException {
                    if (!fileLike.getRelativePath().endsWith(".class")) {
                      return;
                    }

                    boolean belongsInPrimaryDex =
                        primaryDexFilter.matches(
                            fileLike.getRelativePath().replaceAll("\\.class$", ""));
                    String rtype = getResourceType(fileLike.getRelativePath());
                    DeterministicZipBuilder properZip;
                    if (belongsInPrimaryDex) {
                      properZip = outputZips.get("_primarydex");
                    } else if (outputZips.containsKey(rtype)) {
                      properZip = outputZips.get(rtype);
                    } else {
                      properZip = outputZips.get("_other");
                    }
                    Preconditions.checkNotNull(properZip)
                        .addEntry(
                            ByteStreams.toByteArray(fileLike.getInput()),
                            fileLike.getRelativePath(),
                            1);
                  }
                });
      }

      return StepExecutionResult.of(0);
    }

    @Override
    public String getShortName() {
      return "split_uber_r_dot_java_jar";
    }

    @Override
    public String getDescription(ExecutionContext context) {
      return "split_uber_r_dot_java_jar --in "
          + buildContext.getSourcePathResolver().getAbsolutePath(uberRDotJavaJar)
          + " --out "
          + outputPathResolver.getRootPath();
    }
  }

  private String getResourceType(String relativePath) {
    Matcher matcher = RESOURCE_TYPE_PATTERN.matcher(relativePath);
    if (matcher.matches()) {
      return matcher.group(1);
    }
    return "--other--";
  }
}
