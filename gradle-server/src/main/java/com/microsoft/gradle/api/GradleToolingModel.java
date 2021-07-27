package com.microsoft.gradle.api;

import org.gradle.tooling.model.Model;

public interface GradleToolingModel extends Model {
  GradleDependencyNode getDependencyNode();
}
