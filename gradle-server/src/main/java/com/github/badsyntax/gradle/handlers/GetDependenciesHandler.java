package com.github.badsyntax.gradle.handlers;

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
import com.microsoft.gradle.api.GradleDependencyNode;
import com.microsoft.gradle.api.GradleModelAction;
import com.microsoft.gradle.api.GradleToolingModel;
import io.grpc.stub.StreamObserver;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.IOUtils;
import org.gradle.tooling.BuildActionExecuter;
import org.gradle.tooling.BuildCancelledException;
import org.gradle.tooling.CancellationToken;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.events.OperationType;
import org.gradle.tooling.events.ProgressEvent;
import org.gradle.tooling.events.ProgressListener;
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
      BuildActionExecuter<GradleToolingModel> action = connection.action(new GradleModelAction());
      File initScript = File.createTempFile("init-build", ".gradle");
      initScript.deleteOnExit();
      File pluginFile = File.createTempFile("custom-plugin", ".jar");
      pluginFile.deleteOnExit();
      CreateTempFileFromResource("/plugin.jar", pluginFile);
      initScriptFromResource(pluginFile, initScript);
      action.withArguments("--init-script", initScript.getAbsolutePath());
      action.setJavaHome(new File("C:/Program Files/AdoptOpenJDK/jdk-11.0.11.9-hotspot"));
      GradleToolingModel gradleModel = action.run();
      GradleDependencyNode root = gradleModel.getDependencyNode();
      responseObserver.onNext(
          GetDependenciesReply.newBuilder().addAllNode(getProjectDependencyNodes(root)).build());
      responseObserver.onCompleted();
    } catch (BuildCancelledException e) {
      // TODO
    } catch (Exception e) {
      logger.error(e.getMessage());
    } finally {
      GradleBuildCancellation.clearToken(req.getCancellationKey());
    }
  }

  private void CreateTempFileFromResource(String classpath, File outputFile) throws IOException {
    InputStream input = GetDependenciesHandler.class.getResourceAsStream(classpath);
    OutputStream output = new BufferedOutputStream(new FileOutputStream(outputFile));
    IOUtils.copy(input, output);
    input.close();
    output.close();
  }

  private void initScriptFromResource(File extractedJarFile, File outputFile) throws IOException {
    InputStream input = GetDependenciesHandler.class.getResourceAsStream("/initScript.gradle");

    // replace token with extracted file, replace '\' with '/' to handle Windows
    // paths
    String processed =
        IOUtils.toString(input)
            .replace("#PLUGIN_JAR#", extractedJarFile.getAbsolutePath())
            .replace("\\", "/");
    input.close();

    InputStream processedInput = IOUtils.toInputStream(processed);

    OutputStream output = new BufferedOutputStream(new FileOutputStream(outputFile));
    IOUtils.copy(processedInput, output);

    output.close();
    processedInput.close();
  }

  private List<DependencyNode> getProjectDependencyNodes(GradleDependencyNode root) {
    String name = root.getName();
    if (name == null) {
      name = "";
    }
    String group = root.getGroup();
    if (group == null) {
      group = "";
    }
    String id = root.getId();
    if (id == null) {
      id = "";
    }
    String version = root.getVersion();
    if (version == null) {
      version = "";
    }
    String type = root.getType();
    if (type == null) {
      type = "";
    }
    List<DependencyNode> result = new ArrayList<>();
    List<DependencyNode> children = new ArrayList<>();
    for (GradleDependencyNode child : root.getChildren()) {
      if (child.getType().equals("project")) {
        result.addAll(getProjectDependencyNodes(child));
      } else {
        DependencyNode childNode = getDependencyNode(child);
        if (childNode != null) {
          children.add(childNode);
        }
      }
    }
    DependencyNode self =
        DependencyNode.newBuilder()
            .setName(name)
            .setGroup(group)
            .setId(id)
            .setVersion(version)
            .setType(type)
            .addAllChildren(children)
            .build();
    result.add(self);
    return result;
  }

  private DependencyNode getDependencyNode(GradleDependencyNode node) {
    String name = node.getName();
    if (name == null) {
      name = "";
    }
    String group = node.getGroup();
    if (group == null) {
      group = "";
    }
    String id = node.getId();
    if (id == null) {
      id = "";
    }
    String version = node.getVersion();
    if (version == null) {
      version = "";
    }
    String type = node.getType();
    if (type == null) {
      type = "";
    }
    List<DependencyNode> children = new ArrayList<>();
    if (node.getChildren() != null) {
      for (GradleDependencyNode child : node.getChildren()) {
        DependencyNode childNode = getDependencyNode(child);
        if (childNode != null) {
          children.add(childNode);
        }
      }
    } else if (type.equals("configuration")) {
      return null;
    }

    return DependencyNode.newBuilder()
        .setName(name)
        .setGroup(group)
        .setId(id)
        .setVersion(version)
        .setType(type)
        .addAllChildren(children)
        .build();
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
