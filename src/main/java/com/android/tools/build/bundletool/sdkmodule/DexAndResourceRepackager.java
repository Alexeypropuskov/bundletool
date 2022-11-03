/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.tools.build.bundletool.sdkmodule;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.android.bundle.RuntimeEnabledSdkConfigProto.RuntimeEnabledSdk;
import com.android.bundle.SdkModulesConfigOuterClass.SdkModulesConfig;
import com.android.tools.build.bundletool.model.BundleModule;
import com.android.tools.build.bundletool.model.ModuleEntry;
import com.android.tools.build.bundletool.model.ZipPath;
import com.android.tools.build.bundletool.xml.XmlUtils;
import com.google.common.io.ByteSource;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Moves dex files and java resources of the given SDK module to assets, so that they are not loaded
 * with the app's class loader. Also adds CompatSdkConfig.xml metadata file, which is a text XML
 * file necessary for the backwards compatibility library to load the SDK module.
 */
public final class DexAndResourceRepackager {

  private static final String COMPAT_CONFIG_ELEMENT_NAME = "compat-config";
  private static final String COMPAT_ENTRYPOINT_ELEMENT_NAME = "compat-entrypoint";
  private static final String DEX_PATH_ELEMENT_NAME = "dex-path";
  private static final String JAVA_RESOURCE_PATH_ELEMENT_NAME = "java-resource-path";
  // Element which contains the package ID that SDK resource IDs should be remapped to.
  private static final String RESOURCES_PACKAGE_ID_ELEMENT_NAME = "resources-package-id";
  // Element which contains the fully qualified name of the RPackage class of the SDK, where the new
  // resources package ID should be set at app runtime.
  private static final String R_PACKAGE_CLASS_NAME_ELEMENT_NAME = "r-package-class";

  private static final String R_PACKAGE_CLASS_NAME = "RPackage";

  /**
   * Name of the config file that contains paths to moved dex files and java resources inside assets
   * folder, as well as the path to the SDK entrypoint class. Example of what CompatSdkConfig.xml
   * contents look like:
   *
   * <pre>{@code
   * <compat-config>
   *   <dex-path>RuntimeEnabledSdk-sdk.package.name/classes.dex</dex-path>
   *   <dex-path>RuntimeEnabledSdk-sdk.package.name/classes2.dex</dex-path>
   *   <java-resource-path>RuntimeEnabledSdk-sdk.package.name/image.png</java-resource-path>
   *   <compat-entrypoint>com.sdk.EntryPointClass</compat-entrypoint>
   *   <resources-package-id>123</resources-package-id>
   *   <r-package-class>sdk.package.name.RPackage</r-package-class>
   * </compat-config>
   * }</pre>
   */
  private static final String COMPAT_SDK_CONFIG_FILE_NAME = "CompatSdkConfig.xml";

  private static final String ASSETS_DIRECTORY = "assets";
  private static final String ASSETS_SUBDIRECTORY_PREFIX = "RuntimeEnabledSdk-";

  private final SdkModulesConfig sdkModulesConfig;
  private final RuntimeEnabledSdk sdkDependencyConfig;
  private final DexRepackager dexRepackager;
  private final JavaResourceRepackager javaResourceRepackager;

  DexAndResourceRepackager(
      SdkModulesConfig sdkModulesConfig, RuntimeEnabledSdk sdkDependencyConfig) {
    this.sdkModulesConfig = sdkModulesConfig;
    this.sdkDependencyConfig = sdkDependencyConfig;
    this.dexRepackager = new DexRepackager(sdkModulesConfig);
    this.javaResourceRepackager = new JavaResourceRepackager(sdkModulesConfig);
  }

  BundleModule repackage(BundleModule module) {
    checkArgument(
        !module.getEntry(getCompatSdkConfigPath()).isPresent(),
        "Unable to generate %s for %s, because file already exists.",
        COMPAT_SDK_CONFIG_FILE_NAME,
        sdkModulesConfig.getSdkPackageName());
    module = dexRepackager.applyMutation(module);
    module = javaResourceRepackager.applyMutation(module);
    return module.toBuilder().addEntry(getCompatSdkConfigModuleEntry(module)).build();
  }

  /**
   * Returns path inside the assets directory to the CompatSdkConfig.xml file that this class
   * generates.
   */
  public static String getCompatSdkConfigPathInAssets(String sdkPackageName) {
    return ASSETS_SUBDIRECTORY_PREFIX + sdkPackageName + "/" + COMPAT_SDK_CONFIG_FILE_NAME;
  }

  private ZipPath getCompatSdkConfigPath() {
    return ZipPath.create(
        ASSETS_DIRECTORY
            + "/"
            + getCompatSdkConfigPathInAssets(sdkModulesConfig.getSdkPackageName()));
  }

  private ModuleEntry getCompatSdkConfigModuleEntry(BundleModule repackagedModule) {
    return ModuleEntry.builder()
        .setPath(getCompatSdkConfigPath())
        .setContent(
            ByteSource.wrap(
                XmlUtils.documentToString(getCompatSdkConfig(repackagedModule)).getBytes(UTF_8)))
        .build();
  }

  private Document getCompatSdkConfig(BundleModule repackagedModule) {
    Document compatSdkConfig;
    try {
      compatSdkConfig = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException(e);
    }
    compatSdkConfig.appendChild(createCompatConfigXmlNode(compatSdkConfig, repackagedModule));
    return compatSdkConfig;
  }

  private Node createCompatConfigXmlNode(Document xmlFactory, BundleModule repackagedModule) {
    Element compatConfigElement = xmlFactory.createElement(COMPAT_CONFIG_ELEMENT_NAME);
    appendCompatEntrypointElement(compatConfigElement, xmlFactory);
    appendResourcesPackageIdElement(compatConfigElement, xmlFactory);
    appendRPackageClassNameElement(compatConfigElement, xmlFactory);
    appendDexPathsToElement(compatConfigElement, xmlFactory, repackagedModule);
    appendJavaResourcePathsToElement(compatConfigElement, xmlFactory, repackagedModule);
    return compatConfigElement;
  }

  private void appendCompatEntrypointElement(Element compatConfigElement, Document xmlFactory) {
    if (!sdkModulesConfig.getCompatSdkProviderClassName().isEmpty()) {
      Element compatEntrypointElement = xmlFactory.createElement(COMPAT_ENTRYPOINT_ELEMENT_NAME);
      compatEntrypointElement.setTextContent(sdkModulesConfig.getCompatSdkProviderClassName());
      compatConfigElement.appendChild(compatEntrypointElement);
    }
  }

  private void appendResourcesPackageIdElement(Element compatConfigElement, Document xmlFactory) {
    Element resourcesPackageIdElement = xmlFactory.createElement(RESOURCES_PACKAGE_ID_ELEMENT_NAME);
    resourcesPackageIdElement.setTextContent(
        Integer.toString(sdkDependencyConfig.getResourcesPackageId()));
    compatConfigElement.appendChild(resourcesPackageIdElement);
  }

  private void appendRPackageClassNameElement(Element compatConfigElement, Document xmlFactory) {
    Element rPackageClassNameElement = xmlFactory.createElement(R_PACKAGE_CLASS_NAME_ELEMENT_NAME);
    rPackageClassNameElement.setTextContent(
        sdkModulesConfig.getSdkPackageName() + "." + R_PACKAGE_CLASS_NAME);
    compatConfigElement.appendChild(rPackageClassNameElement);
  }

  private void appendDexPathsToElement(
      Element compatConfigElement, Document xmlFactory, BundleModule repackagedModule) {
    repackagedModule.getEntries().stream()
        .filter(entry -> entry.getPath().startsWith(dexRepackager.getNewDexDirectoryPath()))
        .map(
            entry -> {
              Element dexPathElement = xmlFactory.createElement(DEX_PATH_ELEMENT_NAME);
              dexPathElement.setTextContent(
                  dexRepackager.getNewDexDirectoryPathInsideAssets()
                      + "/"
                      + entry.getPath().getFileName().toString());
              return dexPathElement;
            })
        .forEach(compatConfigElement::appendChild);
  }

  private void appendJavaResourcePathsToElement(
      Element compatConfigElement, Document xmlFactory, BundleModule repackagedModule) {
    repackagedModule.getEntries().stream()
        .filter(
            entry ->
                entry
                    .getPath()
                    .startsWith(javaResourceRepackager.getNewJavaResourceDirectoryPath()))
        .map(
            entry -> {
              Element javaResourcePathElement =
                  xmlFactory.createElement(JAVA_RESOURCE_PATH_ELEMENT_NAME);
              javaResourcePathElement.setTextContent(
                  javaResourceRepackager.getNewJavaResourceDirectoryPathInsideAssets()
                      + "/"
                      + entry.getPath().getFileName().toString());
              return javaResourcePathElement;
            })
        .forEach(compatConfigElement::appendChild);
  }
}
