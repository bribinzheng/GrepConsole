package krasa.grepconsole.action;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.ide.actions.QuickSwitchSchemeAction;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsFileUtil;
import krasa.grepconsole.plugin.TailHistory;
import krasa.grepconsole.plugin.TailItem;
import krasa.grepconsole.tail.TailRunExecutor;
import krasa.grepconsole.tail.runConfiguration.TailProgramRunner;
import krasa.grepconsole.tail.runConfiguration.TailRunConfigurationType;
import krasa.grepconsole.tail.runConfiguration.TailRunProfileState;
import krasa.grepconsole.tail.runConfiguration.TailUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static krasa.grepconsole.action.TailFileInConsoleAction.openFileInConsole;
import static krasa.grepconsole.action.TailFileInConsoleAction.resolveEncoding;

public class TailQuickSwitchSchemeAction extends QuickSwitchSchemeAction implements DumbAware {
	@Override
	protected void fillActions(Project project, @NotNull DefaultActionGroup defaultActionGroup, @NotNull DataContext dataContext) {
		List<RunConfiguration> configurations = addRunConfigurations(project, defaultActionGroup);
		if (configurations.size() > 0) {
			defaultActionGroup.add(new Separator());
		}

		addTailHistory(project, defaultActionGroup);

		if (defaultActionGroup.getChildrenCount() == 0) {
			ApplicationManager.getApplication().invokeLater(() -> {
				Notification notification = NotificationGroupManager.getInstance().getNotificationGroup("Grep Console").createNotification("No tail in history", MessageType.INFO);
				Notifications.Bus.notify(notification);
			});
		}
	}

	private void addTailHistory(Project project, @NotNull DefaultActionGroup defaultActionGroup) {
		TailHistory state = TailHistory.getState(project);
		List<TailItem> list = new ArrayList<>(state.getItems());
		Collections.reverse(list);
		VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
		File root = null;
		if (projectDir != null) {
			root = new File(projectDir.getPath());
		}
		for (TailItem tailItem : list) {
			File file = new File(tailItem.getPath());
			if (!file.exists()) {
				state.removeFromHistory(tailItem);
				continue;
			}

			String text = tailItem.getPath();
			if (root != null) {
				try {
					text = VcsFileUtil.relativePath(root, file);
				} catch (Exception e) {
					//ok
				}
			}
			if (tailItem.isNewestMatching()) {
				text += " (newest)";
			}
			defaultActionGroup.add(new DumbAwareAction(FileUtil.toSystemIndependentName(text)) {
				@Override
				public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
					state.add(tailItem);

					TailUtils.openAllMatching(file.getPath(), false, file -> openFile(file, project, tailItem));
				}
			});
		}
	}

	@NotNull
	private List<RunConfiguration> addRunConfigurations(Project project, @NotNull DefaultActionGroup defaultActionGroup) {
		List<RunConfiguration> configurations =
				RunManager.getInstance(project).getConfigurationsList(ConfigurationTypeUtil.findConfigurationType(TailRunConfigurationType.class));
		for (RunConfiguration configuration : configurations) {
			DumbAwareAction action = new DumbAwareAction(configuration.getName(), null, configuration.getIcon()) {
				@Override
				public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
					Executor runExecutorInstance = TailRunExecutor.getRunExecutorInstance();
					ProgramRunner<?> runnerById = ProgramRunner.findRunnerById(TailProgramRunner.ID);
					ExecutionEnvironmentBuilder builder = ExecutionEnvironmentBuilder.create(runExecutorInstance, configuration).runner(runnerById);
					try {
						builder.buildAndExecute();
					} catch (ExecutionException e) {
						ApplicationManager.getApplication().invokeLater(() -> {
							Notification notification = NotificationGroupManager.getInstance().getNotificationGroup("Grep Console").createNotification(e.getMessage(), MessageType.WARNING);
							Notifications.Bus.notify(notification);
						});
					}
				}
			};
			defaultActionGroup.add(action);
		}
		return configurations;
	}

	private void openFile(File file, Project project, TailItem tailItem) {
		if (TailRunProfileState.showExistingContent(file, project)) {
			return;
		}
		String encoding = tailItem.getEncoding();
		Charset charset;
		if (encoding != null) {
			charset = resolveEncoding(file, tailItem.isAutodetectEncoding(), encoding);
		} else {
			charset = resolveEncoding(file);
		}
		openFileInConsole(project, file, charset);
	}
}
