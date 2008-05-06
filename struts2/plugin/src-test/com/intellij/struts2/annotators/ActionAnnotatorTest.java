/*
 * Copyright 2008 The authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.struts2.annotators;

import com.intellij.codeInsight.navigation.NavigationGutterIconRenderer;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlTag;
import com.intellij.struts2.StrutsIcons;
import com.intellij.struts2.dom.struts.BasicStrutsHighlightingTestCase;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Tests for {@link ActionAnnotator}
 *
 * @author Yann C�bron
 */
public class ActionAnnotatorTest extends BasicStrutsHighlightingTestCase {

  protected LocalInspectionTool[] getHighlightingInspections() {
    return new LocalInspectionTool[0];
  }

  @NotNull
  protected String getTestDataLocation() {
    return "/gutterJava/actionClass/";
  }

  // TODO why necessary?
  protected void configureModule(final JavaModuleFixtureBuilder moduleBuilder) throws Exception {
    final String path = myFixture.getTempDirPath();
    moduleBuilder.addContentRoot(path);
    new File(path + "/src").mkdir();
    moduleBuilder.addSourceRoot("src");
  }

  /**
   * Checks whether the gutter target elements resolve to the given Action names.
   *
   * @param gutterIconRenderer  Gutter icon renderer to check.
   * @param expectedActionNames Names of the actions.
   */
  private static void checkGutterActionTargetElements(final GutterIconRenderer gutterIconRenderer,
                                                      @NonNls final String... expectedActionNames) {
    assertNotNull(gutterIconRenderer);
    assertEquals(gutterIconRenderer.getIcon(), StrutsIcons.ACTION);

    assertTrue(gutterIconRenderer instanceof NavigationGutterIconRenderer);
    final NavigationGutterIconRenderer gutter = (NavigationGutterIconRenderer) gutterIconRenderer;

    final Set<String> foundActionNames = new HashSet<String>();
    for (final PsiElement psiElement : gutter.getTargetElements()) {
      assertTrue(psiElement + " != XmlTag", psiElement instanceof XmlTag);
      final String actionName = ((XmlTag) psiElement).getAttributeValue("name");
      foundActionNames.add(actionName);
    }

    assertSameElements(foundActionNames, expectedActionNames);
  }

  public void testGutterMyAction() throws Throwable {
    createStrutsFileSet("struts-actionClass.xml");
    final GutterIconRenderer iconRenderer = myFixture.findGutter("/src/MyAction.java");
    checkGutterActionTargetElements(iconRenderer, "myActionPath");
  }

  public void testGutterMyActionMultipleMappings() throws Throwable {
    createStrutsFileSet("struts-actionClass-multiple_mappings.xml");
    final GutterIconRenderer iconRenderer = myFixture.findGutter("/src/MyAction.java");
    checkGutterActionTargetElements(iconRenderer, "myActionPath1", "myActionPath2", "myActionPath3");
  }

}
