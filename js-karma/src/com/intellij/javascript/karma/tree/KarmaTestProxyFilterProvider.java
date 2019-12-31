package com.intellij.javascript.karma.tree;

import com.intellij.execution.filters.AbstractFileHyperlinkFilter;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.testframework.sm.runner.TestProxyFilterProvider;
import com.intellij.javascript.karma.KarmaConfig;
import com.intellij.javascript.karma.filter.KarmaBrowserErrorFilter;
import com.intellij.javascript.karma.filter.KarmaSourceMapStacktraceFilter;
import com.intellij.javascript.karma.server.KarmaServer;
import com.intellij.javascript.testFramework.util.BrowserStacktraceFilters;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class KarmaTestProxyFilterProvider implements TestProxyFilterProvider {

  private final Project myProject;
  private final KarmaServer myKarmaServer;

  public KarmaTestProxyFilterProvider(@NotNull Project project, @Nullable KarmaServer karmaServer) {
    myProject = project;
    myKarmaServer = karmaServer;
  }

  @Nullable
  @Override
  public Filter getFilter(@NotNull String nodeType, @NotNull String nodeName, @Nullable String nodeArguments) {
    String baseDir = myKarmaServer == null ? null : myKarmaServer.getServerSettings().getWorkingDirectorySystemDependent();
    if ("browser".equals(nodeType)) {
      AbstractFileHyperlinkFilter browserFilter = BrowserStacktraceFilters.createFilter(nodeName, myProject, baseDir);
      if (browserFilter != null) {
        return new KarmaSourceMapStacktraceFilter(myProject, baseDir, browserFilter);
      }
    }
    if ("browserError".equals(nodeType)) {
      return getBrowserErrorFilter();
    }
    return null;
  }

  @Nullable
  private Filter getBrowserErrorFilter() {
    KarmaConfig karmaConfig = myKarmaServer != null ? myKarmaServer.getKarmaConfig() : null;
    return karmaConfig != null ? new KarmaBrowserErrorFilter(myProject, karmaConfig) : null;
  }
}
