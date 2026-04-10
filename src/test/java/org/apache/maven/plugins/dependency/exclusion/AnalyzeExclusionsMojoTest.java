/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.dependency.exclusion;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.maven.api.di.Provides;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoParameter;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MojoTest
class AnalyzeExclusionsMojoTest {

    @Inject
    private MavenProject project;

    @Inject
    private MavenSession mavenSession;

    @Inject
    private Log testLog;

    @Mock
    private DependencyGraphBuilder dependencyGraphBuilder;

    @Provides
    private DependencyGraphBuilder dependencyGraphBuilderProvides() {
        return dependencyGraphBuilder;
    }

    @BeforeEach
    void setUp() throws Exception {
        Model model = new Model();
        model.setGroupId("testGroupId");
        model.setArtifactId("testArtifactId");
        model.setVersion("1.0.0");

        when(project.getGroupId()).thenReturn("testGroupId");
        when(project.getArtifactId()).thenReturn("testArtifactId");
        when(project.getVersion()).thenReturn("1.0.0");
        when(project.getModel()).thenReturn(model);

        lenient().when(mavenSession.getRepositorySession()).thenReturn(new DefaultRepositorySystemSession());
        lenient().when(mavenSession.getProjectBuildingRequest()).thenReturn(new DefaultProjectBuildingRequest());
        lenient().doReturn(rootNode()).when(dependencyGraphBuilder).buildDependencyGraph(any(), any());
    }

    @Test
    @InjectMojo(goal = "analyze-exclusions")
    @MojoParameter(name = "exclusionFail", value = "true")
    void testShallThrowExceptionWhenFailOnWarning(AnalyzeExclusionsMojo mojo) {
        List<Dependency> dependencies = new ArrayList<>();
        Dependency withInvalidExclusion = dependency("a", "b");
        withInvalidExclusion.addExclusion(exclusion("invalid", "invalid"));
        dependencies.add(withInvalidExclusion);
        when(project.getDependencies()).thenReturn(dependencies);

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Invalid exclusions found");

        verify(testLog, times(3)).error(anyString());
    }

    @Test
    @InjectMojo(goal = "analyze-exclusions")
    @MojoParameter(name = "exclusionFail", value = "false")
    void testShallLogWarningWhenFailOnWarningIsFalse(AnalyzeExclusionsMojo mojo) throws Exception {
        List<Dependency> dependencies = new ArrayList<>();
        Dependency withInvalidExclusion = dependency("a", "b");
        withInvalidExclusion.addExclusion(exclusion("invalid", "invalid"));
        dependencies.add(withInvalidExclusion);
        when(project.getDependencies()).thenReturn(dependencies);

        mojo.execute();

        verify(testLog, times(3)).warn(anyString());
    }

    @Test
    @InjectMojo(goal = "analyze-exclusions")
    void testShallExitWithoutAnalyzeWhenNoDependencyHasExclusion(AnalyzeExclusionsMojo mojo) throws Exception {
        List<Dependency> dependencies = new ArrayList<>();
        dependencies.add(dependency("a", "c"));
        when(project.getDependencies()).thenReturn(dependencies);

        mojo.execute();
        verify(testLog).debug("No dependencies defined with exclusions - exiting");
    }

    @Test
    @InjectMojo(goal = "analyze-exclusions")
    void testShallNotReportInvalidExclusionForWildcardGroupIdAndArtifactId(AnalyzeExclusionsMojo mojo)
            throws Exception {
        Dependency dependencyWithWildcardExclusion = dependency("a", "b");
        dependencyWithWildcardExclusion.addExclusion(exclusion("*", "*"));
        when(project.getDependencies()).thenReturn(Collections.singletonList(dependencyWithWildcardExclusion));

        doReturn(rootNode(node("whatever", "ok"))).when(dependencyGraphBuilder).buildDependencyGraph(any(), any());

        mojo.execute();
        verify(testLog, never()).warn(anyString());
    }

    @Test
    @InjectMojo(goal = "analyze-exclusions")
    void testCanResolveMultipleArtifactsWithEqualGroupIdAndArtifactId(AnalyzeExclusionsMojo mojo) {
        Dependency dependency1 = dependency("a", "b");
        Dependency dependency2 = dependency("a", "b", "compile", "native");
        dependency1.addExclusion(exclusion("c", "d"));
        dependency2.addExclusion(exclusion("c", "d"));
        when(project.getDependencies()).thenReturn(Arrays.asList(dependency1, dependency2));

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    @Test
    @InjectMojo(goal = "analyze-exclusions")
    void testShallNotLogWhenExclusionIsValid(AnalyzeExclusionsMojo mojo) throws Exception {
        List<Dependency> dependencies = new ArrayList<>();
        Dependency dependency = dependency("a", "b");
        dependency.addExclusion(exclusion("ok", "ok"));
        dependencies.add(dependency);
        when(project.getDependencies()).thenReturn(dependencies);

        doReturn(rootNode(node("ok", "ok"))).when(dependencyGraphBuilder).buildDependencyGraph(any(), any());

        assertThatCode(mojo::execute).doesNotThrowAnyException();

        verify(testLog, never()).warn(anyString());
    }

    @Test
    @InjectMojo(goal = "analyze-exclusions")
    void testThatLogContainProjectName(AnalyzeExclusionsMojo mojo) throws Exception {
        List<Dependency> dependencies = new ArrayList<>();
        Dependency withInvalidExclusion = dependency("a", "b");
        withInvalidExclusion.addExclusion(exclusion("invalid", "invalid"));
        dependencies.add(withInvalidExclusion);
        when(project.getDependencies()).thenReturn(dependencies);

        when(project.getName()).thenReturn("projectName");

        mojo.execute();

        verify(testLog).warn("projectName defines following unnecessary excludes");
    }

    /**
     * Nullability behavior of {@link MavenProject#getDependencyManagement} is not documented, test mojo with both {@code null}
     * and non-{@code null} outputs
     *
     * @see <a href="https://github.com/apache/maven-dependency-plugin/issues/1474">Issue</a>
     */
    @Test
    @InjectMojo(goal = "analyze-exclusions")
    void testMojoWithProjectDependencyManagementNull(AnalyzeExclusionsMojo mojo) {
        // Default behavior specified explicitly for clarity
        when(project.getDependencyManagement()).thenReturn(null);

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    /**
     * Nullability behavior of {@link MavenProject#getDependencyManagement} is not documented, test mojo with both {@code null}
     * and non-{@code null} outputs
     *
     * @see <a href="https://github.com/apache/maven-dependency-plugin/issues/1474">Issue</a>
     */
    @Test
    @InjectMojo(goal = "analyze-exclusions")
    void testMojoWithProjectDependencyManagementEmpty(AnalyzeExclusionsMojo mojo) {
        DependencyManagement dependencyManagement = mock(DependencyManagement.class);
        lenient().when(dependencyManagement.getDependencies()).thenReturn(Collections.emptyList());
        when(project.getDependencyManagement()).thenReturn(dependencyManagement);

        assertThatCode(mojo::execute).doesNotThrowAnyException();
    }

    private Dependency dependency(String groupId, String artifactId) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion("1.0");
        dependency.setScope("compile");
        dependency.setType("jar");
        dependency.setClassifier("");
        dependency.setLocation("", new InputLocation(1, 1));
        return dependency;
    }

    private Dependency dependency(String groupId, String artifactId, String scope, String classifier) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion("1.0");
        dependency.setScope(scope);
        dependency.setType("jar");
        dependency.setClassifier(classifier);
        dependency.setLocation("", new InputLocation(1, 1));
        return dependency;
    }

    private Exclusion exclusion(String groupId, String artifactId) {
        Exclusion exclusion = new Exclusion();
        exclusion.setGroupId(groupId);
        exclusion.setArtifactId(artifactId);
        InputSource inputSource = new InputSource();
        inputSource.setModelId("testGroupId:testArtifactId:1.0.0");
        exclusion.setLocation("", new InputLocation(1, 1, inputSource));
        return exclusion;
    }

    private DependencyNode rootNode(DependencyNode... children) {
        DependencyNode root = mock(DependencyNode.class);
        lenient().when(root.getChildren()).thenReturn(Arrays.asList(children));
        return root;
    }

    private DependencyNode node(String groupId, String artifactId, DependencyNode... children) {
        Artifact artifact = mock(Artifact.class);
        lenient().when(artifact.getGroupId()).thenReturn(groupId);
        lenient().when(artifact.getArtifactId()).thenReturn(artifactId);

        DependencyNode node = mock(DependencyNode.class);
        lenient().when(node.getArtifact()).thenReturn(artifact);
        lenient().when(node.getChildren()).thenReturn(Arrays.asList(children));
        return node;
    }
}
