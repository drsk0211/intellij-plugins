package com.jetbrains.cidr.cpp.embedded.platformio.project;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.ide.util.projectWizard.AbstractNewProjectStep;
import com.intellij.ide.util.projectWizard.CustomStepProjectGenerator;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.impl.welcomeScreen.AbstractActionWithPanel;
import com.intellij.platform.DirectoryProjectGenerator;
import com.intellij.platform.GeneratorPeerImpl;
import com.intellij.platform.ProjectGeneratorPeer;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.messages.MessageBusConnection;
import com.jetbrains.cidr.cpp.cmake.CMakeSettings;
import com.jetbrains.cidr.cpp.cmake.model.CMakeTarget;
import com.jetbrains.cidr.cpp.cmake.projectWizard.generators.CLionProjectGenerator;
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace;
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspaceListener;
import com.jetbrains.cidr.cpp.embedded.platformio.CustomTool;
import com.jetbrains.cidr.cpp.embedded.platformio.PlatformioBaseConfiguration;
import com.jetbrains.cidr.cpp.embedded.platformio.PlatformioConfigurationType;
import com.jetbrains.cidr.cpp.execution.CMakeBuildConfigurationHelper;
import com.jetbrains.cidr.cpp.execution.CMakeRunConfigurationType;
import com.jetbrains.cidr.execution.BuildTargetAndConfigurationData;
import com.jetbrains.cidr.execution.BuildTargetData;
import com.jetbrains.cidr.execution.ExecutableData;
import icons.ClionEmbeddedPlatformioIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static com.jetbrains.cidr.cpp.embedded.stm32cubemx.CMakeSTM32CubeMXProjectGenerator.EMBEDDED_PROJECTS_GROUP_NAME;

public class PlatformioProjectGenerator extends CLionProjectGenerator<Ref<String[]>> implements CustomStepProjectGenerator<Ref<String[]>> {

  @Override
  public AbstractActionWithPanel createStep(DirectoryProjectGenerator<Ref<String[]>> projectGenerator,
                                            AbstractNewProjectStep.AbstractCallback<Ref<String[]>> callback) {
    return new PlatformioProjectSettingsStep(projectGenerator, callback);
  }

  @Nullable
  @Override
  public Icon getLogo() {
    return ClionEmbeddedPlatformioIcons.Platformio;
  }

  @NotNull
  @Override
  public String getGroupName() {
    return EMBEDDED_PROJECTS_GROUP_NAME;
  }

  @NotNull
  @Override
  public String getName() {
    return "PlatformIO";
  }

  @Override
  public String getDescription() {
    return "https://platformio.org/";
  }

  @NotNull
  @Override
  public ProjectGeneratorPeer<Ref<String[]>> createPeer() {
    return new GeneratorPeerImpl<>(new Ref<>(null), new JPanel());
  }

  @Override
  public void generateProject(@NotNull Project project,
                              @NotNull VirtualFile baseDir,
                              @NotNull Ref<String[]> settings,
                              @NotNull Module module) {
    /* This method starts multi-stage process
       1. PlatformIO utility is started asynchronously under progress indicator
       2. When it's done, another asynchronous code writes empty source code stub if no main.c or main.cpp is generated
       3. When it's done, CMake workspace is initialized asynchronously, and a listener is set to watch the process
       4. When it's done, CMake run configurations and build profiles are created
     */
    String myPioUtility = PlatformioBaseConfiguration.findPlatformio();
    if (myPioUtility == null) {
      showError("PlatformIO utility is not found");
      return;
    }
    CustomTool initTool = new CustomTool("PlatformIO init");
    initTool.setProgram(myPioUtility);
    initTool.setWorkingDirectory(baseDir.getCanonicalPath());
    initTool.setParameters("init --ide clion " + String.join(" ", settings.get()));
    DataContext projectContext = SimpleDataContext.getProjectContext(project);
    Semaphore semaphore = new Semaphore(1);
    Ref<Boolean> success = new Ref<>(false);
    ProcessAdapter processListener = new ProcessAdapter() {
      @Override
      public void processTerminated(@NotNull ProcessEvent event) {
        success.set(event.getExitCode() == 0);
        semaphore.up();
      }
    };
    if (initTool.executeIfPossible(null, projectContext, -1, processListener)) {
      new Task.Backgroundable(project, "Initializing", true, PerformInBackgroundOption.DEAF) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          while (!semaphore.waitFor(200)) {
            indicator.checkCanceled();
          }
          baseDir.refresh(false, true);
        }

        @Override
        public void onFinished() {
          if (success.get()) {
            // Phase 2 started
            WriteAction.run(() -> finishFileStructure(project, baseDir));
          }
        }
      }.queue(); // Phase 1 started
    }
  }

  private void finishFileStructure(@NotNull Project project,
                                   @NotNull VirtualFile baseDir) {
    VirtualFile srcFolder = baseDir.findChild("src");
    if (srcFolder == null || !srcFolder.isDirectory()) {
      showError("Src folder is not found or invalid");
      return;
    }
    if (srcFolder.findChild("main.cpp") == null) {
      try {
        VirtualFile mainC = srcFolder.findOrCreateChildData(this, "main.c");
        if (mainC.getLength() == 0) {
          mainC.setBinaryContent("# Write your code here".getBytes(StandardCharsets.US_ASCII));
        }
      }
      catch (IOException e) {
        showError(ExceptionUtil.getThrowableText(e));
        return;
      }
    }
    if (project.isInitialized()) {
      CMakeWorkspace cmakeWorkspace = CMakeWorkspace.getInstance(project);
      MessageBusConnection busConnection = project.getMessageBus().connect();
      busConnection.subscribe(CMakeWorkspaceListener.TOPIC, new CMakeWorkspaceListener() {

        @Override
        public void reloadingFinished(boolean canceled) {
          busConnection.disconnect();
          if (!canceled && project.isInitialized()) {
            //Phase 4
            configureBuildTypes(cmakeWorkspace);
            configureRunConfigurations(project);
          }
        }
      });
      cmakeWorkspace.selectProjectDir(VfsUtilCore.virtualToIoFile(baseDir)); //Phase 3 started
    }
  }

  private static void configureRunConfigurations(@NotNull Project project) {
    RunManager runManager = RunManager.getInstance(project);

    final CMakeBuildConfigurationHelper helper = CMakeRunConfigurationType.getHelper(project);

    ConfigurationFactory[] factories =
      ConfigurationTypeUtil.findConfigurationType(PlatformioConfigurationType.class).getNewProjectFactories();
    for (int i = 0; i < factories.length; i++) {
      ConfigurationFactory factory = factories[i];
      String name = factory.getName();
      if (runManager.findConfigurationByName(name) == null) {
        RunnerAndConfigurationSettings runSettings = runManager.createConfiguration(name, factory);

        PlatformioBaseConfiguration configuration = (PlatformioBaseConfiguration)runSettings.getConfiguration();
        CMakeTarget target = helper.findFirstSuitableTarget(configuration.getCmakeBuildTarget());
        if (target != null) {
          final BuildTargetData buildTargetData = new BuildTargetData(project.getName(), target.getName());
          final BuildTargetAndConfigurationData data = new BuildTargetAndConfigurationData(buildTargetData, null);
          configuration.setTargetAndConfigurationData(data);
          configuration.setExecutableData(new ExecutableData(buildTargetData));
          runManager.addConfiguration(runSettings);
          if (i == 0) {
            runManager.setSelectedConfiguration(runSettings);
          }
        }
      }
    }
  }

  private static void configureBuildTypes(@NotNull CMakeWorkspace cmakeWorkspace) {
    CMakeSettings settings = cmakeWorkspace.getSettings();
    List<CMakeSettings.Profile> profiles = cmakeWorkspace.getModelConfigurationData()
      .stream()
      .flatMap(modelConfigurationData -> modelConfigurationData.getRegisteredBuildTypes().stream())
      .map(buildType -> new CMakeSettings.Profile(buildType))
      .collect(Collectors.toList());
    settings.setProfiles(profiles);
  }

  private static void showError(@NotNull String message) {
    Notification notification =
      new Notification("PlatformIO plugin", "Project init failed",
                       message, NotificationType.WARNING);
    Notifications.Bus.notify(notification);
  }
}
