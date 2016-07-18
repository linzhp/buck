/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.android.aapt.MergeAndroidResourceSources;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.Set;

/**
 * Description for a {@link BuildRule} that generates an {@code .aar} file.
 * <p/>
 * This represents an Android Library Project packaged as an {@code .aar} bundle as specified by:
 * <a> http://tools.android.com/tech-docs/new-build-system/aar-format</a>.
 * <p/>
 * Note that the {@code aar} may be specified as a {@link SourcePath}, so it could be either
 * a binary {@code .aar} file checked into version control, or a zip file that conforms to the
 * {@code .aar} specification that is generated by another build rule.
 */
public class AndroidAarDescription implements Description<AndroidAarDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("android_aar");

  private static final Flavor AAR_ANDROID_MANIFEST_FLAVOR =
      ImmutableFlavor.of("aar_android_manifest");
  private static final Flavor AAR_ASSEMBLE_RESOURCE_FLAVOR =
      ImmutableFlavor.of("aar_assemble_resource");
  private static final Flavor AAR_ASSEMBLE_ASSETS_FLAVOR =
      ImmutableFlavor.of("aar_assemble_assets");
  private static final Flavor AAR_ANDROID_RESOURCE_FLAVOR =
      ImmutableFlavor.of("aar_android_resource");

  private final AndroidManifestDescription androidManifestDescription;
  private final CxxBuckConfig cxxBuckConfig;
  private final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms;

  public AndroidAarDescription(
      AndroidManifestDescription androidManifestDescription,
      CxxBuckConfig cxxBuckConfig,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms) {
    this.androidManifestDescription = androidManifestDescription;
    this.cxxBuckConfig = cxxBuckConfig;
    this.nativePlatforms = nativePlatforms;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams originalBuildRuleParams,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    UnflavoredBuildTarget originalBuildTarget =
        originalBuildRuleParams.getBuildTarget().checkUnflavored();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableList.Builder<BuildRule> aarExtraDepsBuilder = ImmutableList.<BuildRule>builder()
        .addAll(originalBuildRuleParams.getExtraDeps().get());

    /* android_manifest */
    AndroidManifestDescription.Arg androidManifestArgs =
        androidManifestDescription.createUnpopulatedConstructorArg();
    androidManifestArgs.skeleton = args.manifestSkeleton;
    androidManifestArgs.deps = args.deps;

    BuildRuleParams androidManifestParams = originalBuildRuleParams.copyWithChanges(
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_ANDROID_MANIFEST_FLAVOR),
        originalBuildRuleParams.getDeclaredDeps(),
        originalBuildRuleParams.getExtraDeps());

    AndroidManifest manifest = androidManifestDescription.createBuildRule(
        targetGraph,
        androidManifestParams,
        resolver,
        androidManifestArgs);
    aarExtraDepsBuilder.add(resolver.addToIndex(manifest));

    /* assemble dirs */
    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            originalBuildRuleParams.getBuildTarget(),
            /* buildTargetsToExcludeFromDex */ ImmutableSet.<BuildTarget>of(),
            /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
            new APKModuleGraph(
                targetGraph,
                originalBuildRuleParams.getBuildTarget(),
                Optional.<Set<BuildTarget>>absent()));
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(
            originalBuildRuleParams.getDeps()));
    AndroidPackageableCollection packageableCollection = collector.build();

    ImmutableSortedSet<BuildRule> androidResourceDeclaredDeps =
        AndroidResourceHelper.androidResOnly(originalBuildRuleParams.getDeclaredDeps().get());
    ImmutableSortedSet<BuildRule> androidResourceExtraDeps =
        AndroidResourceHelper.androidResOnly(originalBuildRuleParams.getExtraDeps().get());

    BuildRuleParams assembleAssetsParams = originalBuildRuleParams.copyWithChanges(
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_ASSEMBLE_ASSETS_FLAVOR),
        Suppliers.ofInstance(androidResourceDeclaredDeps),
        Suppliers.ofInstance(androidResourceExtraDeps));
    ImmutableCollection<SourcePath> assetsDirectories =
        packageableCollection.getAssetsDirectories();
    AssembleDirectories assembleAssetsDirectories = new AssembleDirectories(
        assembleAssetsParams,
        pathResolver,
        assetsDirectories);
    aarExtraDepsBuilder.add(resolver.addToIndex(assembleAssetsDirectories));

    BuildRuleParams assembleResourceParams = originalBuildRuleParams.copyWithChanges(
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_ASSEMBLE_RESOURCE_FLAVOR),
        Suppliers.ofInstance(androidResourceDeclaredDeps),
        Suppliers.ofInstance(androidResourceExtraDeps));
    ImmutableCollection<SourcePath> resDirectories =
        packageableCollection.getResourceDetails().getResourceDirectories();
    MergeAndroidResourceSources assembleResourceDirectories = new MergeAndroidResourceSources(
        assembleResourceParams,
        pathResolver,
        resDirectories);
    aarExtraDepsBuilder.add(resolver.addToIndex(assembleResourceDirectories));

    /* android_resource */
    BuildRuleParams androidResourceParams = originalBuildRuleParams.copyWithChanges(
        BuildTargets.createFlavoredBuildTarget(originalBuildTarget, AAR_ANDROID_RESOURCE_FLAVOR),
        Suppliers.ofInstance(
            ImmutableSortedSet.<BuildRule>of(
                manifest,
                assembleAssetsDirectories,
                assembleResourceDirectories)),
        Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()));

    AndroidResource androidResource = new AndroidResource(
        androidResourceParams,
        pathResolver,
        /* deps */ ImmutableSortedSet.<BuildRule>naturalOrder()
        .add(assembleAssetsDirectories)
        .add(assembleResourceDirectories)
        .addAll(originalBuildRuleParams.getDeclaredDeps().get())
        .build(),
        new BuildTargetSourcePath(assembleResourceDirectories.getBuildTarget()),
        /* resSrcs */ ImmutableSortedSet.<SourcePath>of(),
        Optional.<SourcePath>absent(),
        /* rDotJavaPackage */ null,
        new BuildTargetSourcePath(assembleAssetsDirectories.getBuildTarget()),
        /* assetsSrcs */ ImmutableSortedSet.<SourcePath>of(),
        Optional.<SourcePath>absent(),
        new BuildTargetSourcePath(manifest.getBuildTarget()),
        /* hasWhitelistedStrings */ false);
    aarExtraDepsBuilder.add(resolver.addToIndex(androidResource));

    /* native_libraries */
    AndroidNativeLibsPackageableGraphEnhancer packageableGraphEnhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            resolver,
            originalBuildRuleParams,
            nativePlatforms,
            ImmutableSet.<NdkCxxPlatforms.TargetCpuType>of(),
            cxxBuckConfig,
            AndroidBinary.RelinkerMode.DISABLED);
    Optional<CopyNativeLibraries> nativeLibrariesOptional =
        packageableGraphEnhancer.getCopyNativeLibraries(packageableCollection);
    if (nativeLibrariesOptional.isPresent()) {
      aarExtraDepsBuilder.add(resolver.addToIndex(nativeLibrariesOptional.get()));
    }

    Optional<Path> assembledNativeLibsDir = nativeLibrariesOptional.transform(
        new Function<CopyNativeLibraries, Path>() {
          @Override
          public Path apply(CopyNativeLibraries input) {
            return input.getPathToNativeLibsDir();
          }
        });
    BuildRuleParams androidAarParams = originalBuildRuleParams.copyWithExtraDeps(
        Suppliers.ofInstance(ImmutableSortedSet.copyOf(aarExtraDepsBuilder.build())));
    return new AndroidAar(
        androidAarParams,
        pathResolver,
        manifest,
        androidResource,
        assembleResourceDirectories.getPathToOutput(),
        assembleAssetsDirectories.getPathToOutput(),
        assembledNativeLibsDir,
        packageableCollection.getNativeLibAssetsDirectories());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AndroidLibraryDescription.Arg {
    public SourcePath manifestSkeleton;
  }
}
