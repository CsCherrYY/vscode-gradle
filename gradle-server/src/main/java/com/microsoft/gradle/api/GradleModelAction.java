package com.microsoft.gradle.api;

import java.io.Serializable;
import org.gradle.tooling.BuildAction;
import org.gradle.tooling.BuildController;

public class GradleModelAction implements Serializable, BuildAction {
  @Override
  public GradleToolingModel execute(BuildController controller) {
    return controller.getModel(GradleToolingModel.class);
  }
}
