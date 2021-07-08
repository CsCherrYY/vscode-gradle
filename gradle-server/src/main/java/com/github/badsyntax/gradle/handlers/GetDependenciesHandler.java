package com.github.badsyntax.gradle.handlers;

import com.avast.server.libver.model.gradle.GradleDepsDescriptor;
import com.avast.server.libver.model.gradle.Node;
import com.avast.server.libver.model.gradle.Subproject;
import com.avast.server.libver.service.impl.GradleParser;
import com.github.badsyntax.gradle.ByteBufferOutputStream;
import com.github.badsyntax.gradle.DependencyNode;
import com.github.badsyntax.gradle.ErrorMessageBuilder;
import com.github.badsyntax.gradle.GetDependenciesReply;
import com.github.badsyntax.gradle.GetDependenciesRequest;
import com.github.badsyntax.gradle.GradleBuildCancellation;
import com.github.badsyntax.gradle.GradleProjectConnector;
import com.github.badsyntax.gradle.exceptions.GradleConnectionException;
import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gradle.tooling.BuildCancelledException;
import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.CancellationToken;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.events.OperationType;
import org.gradle.tooling.events.ProgressEvent;
import org.gradle.tooling.events.ProgressListener;
import org.gradle.tooling.model.DomainObjectSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetDependenciesHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(GetDependenciesHandler.class.getName());

  private GetDependenciesRequest req;
  private StreamObserver<GetDependenciesReply> responseObserver;
  private ProgressListener progressListener;
  private ByteBufferOutputStream standardOutputListener;
  private ByteBufferOutputStream standardErrorListener;
  private String result = "";

  public GetDependenciesHandler(
      GetDependenciesRequest req, StreamObserver<GetDependenciesReply> responseObserver) {
    this.req = req;
    this.responseObserver = responseObserver;
    this.progressListener =
        (ProgressEvent event) -> {
          synchronized (GetBuildHandler.class) {
            replyWithProgress(event);
          }
        };
    this.standardOutputListener =
        new ByteBufferOutputStream() {
          @Override
          public void onFlush(byte[] bytes) {
            synchronized (GetBuildHandler.class) {
              replyWithStandardOutput(bytes);
            }
          }
        };
    this.standardErrorListener =
        new ByteBufferOutputStream() {
          @Override
          public void onFlush(byte[] bytes) {
            synchronized (GetBuildHandler.class) {
              replyWithStandardError(bytes);
            }
          }
        };
  }

  public void run() {
    GradleConnector gradleConnector;
    try {
      gradleConnector = GradleProjectConnector.build(req.getProjectDir(), req.getGradleConfig());
    } catch (GradleConnectionException e) {
      logger.error(e.getMessage());
      responseObserver.onError(ErrorMessageBuilder.build(e));
      return;
    }

    try (ProjectConnection connection = gradleConnector.connect()) {
      org.gradle.tooling.model.GradleProject rootProject = getGradleProject(connection);
      String rootname = rootProject.getName();
      List<DependencyNode> resultList = new ArrayList<>();
      BuildLauncher build = connection.newBuild();
      build.addProgressListener(progressListener);
      build.setStandardOutput(standardOutputListener);
      build.forTasks("dependencies");
      build.run();
      GradleDepsDescriptor gradleDepsDescriptor = new GradleParser(this.result).parse();
      DependencyNode resultNode = getDependencyNode(gradleDepsDescriptor, rootname);
      resultList.add(resultNode);
      DomainObjectSet<? extends org.gradle.tooling.model.GradleProject> children =
          rootProject.getChildren();
      for (org.gradle.tooling.model.GradleProject child : children) {
        String name = child.getName();
        this.result = "";
        build.forTasks(name + ":dependencies");
        build.run();
        gradleDepsDescriptor = new GradleParser(this.result).parse();
        resultNode = getDependencyNode(gradleDepsDescriptor, name);
        resultList.add(resultNode);
      }
      responseObserver.onNext(GetDependenciesReply.newBuilder().addAllNode(resultList).build());
      responseObserver.onCompleted();
    } catch (BuildCancelledException e) {
      // TODO
    } catch (Exception e) {
      logger.error(e.getMessage());
    } finally {
      GradleBuildCancellation.clearToken(req.getCancellationKey());
    }
  }

  private DependencyNode getDependencyNode(
      GradleDepsDescriptor gradleDepsDescriptor, String projectName) {
    Map<String, Subproject> projectMap = gradleDepsDescriptor.getProjects();
    for (Map.Entry<String, Subproject> entry : projectMap.entrySet()) {
      Subproject project = entry.getValue();
      List<DependencyNode> subNodes = getDependencyNode(project);
      return DependencyNode.newBuilder()
          .setName(projectName)
          .setLevel(0)
          .addAllChildren(subNodes)
          .build();
    }
    return null;
  }

  private List<DependencyNode> getDependencyNode(Subproject project) {
    List<DependencyNode> result = new ArrayList<>();
    Map<String, List<Node>> sourceSets = project.getSourceSet();
    for (Map.Entry<String, List<Node>> sourceSet : sourceSets.entrySet()) {
      String name = sourceSet.getKey();
      List<Node> nodes = sourceSet.getValue();
      List<DependencyNode> children = new ArrayList<>();
      for (Node node : nodes) {
        DependencyNode childNode = getDependencyNode(node);
        if (childNode == null) {
          continue;
        }
        children.add(childNode);
      }
      if (children.size() == 0) {
        continue;
      }
      DependencyNode node =
          DependencyNode.newBuilder().setName(name).setLevel(1).addAllChildren(children).build();
      result.add(node);
    }
    return result;
  }

  private DependencyNode getDependencyNode(Node node) {
    String text = node.getText();
    if (text.contains("(n)")) {
      return null;
    }
    DependencyNode dependencyNode;
    if (node.getChildren() == null || node.getChildren().isEmpty()) {
      dependencyNode = DependencyNode.newBuilder().setName(node.getText()).setLevel(2).build();
    } else {
      List<DependencyNode> children = new ArrayList<>();
      for (Node child : node.getChildren()) {
        children.add(getDependencyNode(child));
      }
      dependencyNode =
          DependencyNode.newBuilder()
              .setName(node.getText())
              .setLevel(2)
              .addAllChildren(children)
              .build();
    }
    return dependencyNode;
  }

  private void replyWithProgress(ProgressEvent progressEvent) {
    // TODO
  }

  private void replyWithStandardOutput(byte[] bytes) {
    ByteString byteString = ByteString.copyFrom(bytes);
    this.result += byteString.toStringUtf8();
    // responseObserver.onNext(GetDependenciesReply.newBuilder().no(byteString.toStringUtf8()).build());
  }

  private void replyWithStandardError(byte[] bytes) {
    // TODO
  }

  private org.gradle.tooling.model.GradleProject getGradleProject(ProjectConnection connection)
      throws IOException {

    ModelBuilder<org.gradle.tooling.model.GradleProject> projectBuilder =
        connection.model(org.gradle.tooling.model.GradleProject.class);

    Set<OperationType> progressEvents = new HashSet<>();
    progressEvents.add(OperationType.PROJECT_CONFIGURATION);

    CancellationToken cancellationToken =
        GradleBuildCancellation.buildToken(req.getCancellationKey());

    projectBuilder
        .withCancellationToken(cancellationToken)
        .addProgressListener(progressListener, progressEvents)
        .setStandardOutput(standardOutputListener)
        .setStandardError(standardErrorListener);
    if (!Strings.isNullOrEmpty(req.getGradleConfig().getJvmArguments())) {
      projectBuilder.setJvmArguments(req.getGradleConfig().getJvmArguments());
    }

    try {
      return projectBuilder.get();
    } finally {
      GradleBuildCancellation.clearToken(req.getCancellationKey());
    }
  }
}
