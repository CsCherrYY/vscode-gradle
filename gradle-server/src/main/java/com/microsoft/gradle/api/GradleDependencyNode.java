package com.microsoft.gradle.api;

import java.util.List;

public interface GradleDependencyNode {

  String getName();

  GradleDependencyNode getParent();

  String getGroup();

  String getId();

  String getVersion();

  String getType();

  boolean getSeenBefore();

  List<GradleDependencyNode> getChildren();
}
