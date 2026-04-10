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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.apache.maven.plugins.dependency.exclusion.Coordinates.coordinates;

/**
 * Analyzes the exclusions defined on dependencies in this project and reports if any of them are unneeded.
 * <p>
 * Relevant use case is when an artifact in a later version has removed usage of a dependency, making the exclusion no
 * longer necessary.
 * </p>
 *
 * @since 3.7.0
 */
@Mojo(name = "analyze-exclusions", requiresDependencyCollection = ResolutionScope.TEST, threadSafe = true)
public class AnalyzeExclusionsMojo extends AbstractMojo {

    private final MavenProject project;

    private final DependencyGraphBuilder dependencyGraphBuilder;

    private final MavenSession session;

    @Inject
    public AnalyzeExclusionsMojo(
            MavenProject project, DependencyGraphBuilder dependencyGraphBuilder, MavenSession session) {
        this.project = project;
        this.dependencyGraphBuilder = dependencyGraphBuilder;
        this.session = session;
    }

    /**
     * Whether to fail the build if invalid exclusions is found.
     *
     * @since 3.7.0
     */
    @Parameter(property = "mdep.exclusion.fail", defaultValue = "false")
    private boolean exclusionFail;

    /**
     * Skip plugin execution completely.
     *
     * @since 3.7.0
     */
    @Parameter(property = "mdep.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Current project modelId.
     */
    private String projectModelId;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().debug("Skipping execution");
            return;
        }

        projectModelId = project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion();

        Map<Coordinates, Collection<Exclusion>> dependenciesWithExclusions = new HashMap<>();

        DependencyManagement depMgt = project.getDependencyManagement();
        if (depMgt != null) {
            List<Dependency> depMgtDependencies = depMgt.getDependencies();

            if (depMgtDependencies != null) {
                depMgtDependencies.forEach(dependency -> {
                    Collection<Exclusion> exclusions = getExclusionsForDependency(dependency);
                    if (!exclusions.isEmpty()) {
                        dependenciesWithExclusions
                                .computeIfAbsent(coordinates(dependency), d -> new ArrayList<>())
                                .addAll(exclusions);
                    }
                });
            }
        }

        project.getDependencies().forEach(dependency -> {
            Collection<Exclusion> exclusions = getExclusionsForDependency(dependency);
            if (!exclusions.isEmpty()) {
                dependenciesWithExclusions
                        .computeIfAbsent(coordinates(dependency), d -> new ArrayList<>())
                        .addAll(exclusions);
            }
        });

        if (dependenciesWithExclusions.isEmpty()) {
            getLog().debug("No dependencies defined with exclusions - exiting");
            return;
        }

        ExclusionChecker checker = new ExclusionChecker();

        for (Map.Entry<Coordinates, Collection<Exclusion>> entry : dependenciesWithExclusions.entrySet()) {

            Coordinates currentCoordinates = entry.getKey();

            try {
                Set<Coordinates> actualCoordinates = collectActualCoordinates(currentCoordinates);

                Set<Coordinates> exclusions =
                        entry.getValue().stream().map(Coordinates::coordinates).collect(toSet());

                checker.check(currentCoordinates, exclusions, actualCoordinates);
            } catch (DependencyGraphBuilderException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }

        if (!checker.getViolations().isEmpty()) {
            if (exclusionFail) {
                logViolations(project.getName(), checker.getViolations(), value -> getLog().error(value));
                throw new MojoExecutionException("Invalid exclusions found");
            } else {
                logViolations(project.getName(), checker.getViolations(), value -> getLog().warn(value));
            }
        } else {
            getLog().info("No problems with dependencies exclusions");
        }
    }

    private Collection<Exclusion> getExclusionsForDependency(Dependency dependency) {
        return dependency.getExclusions().stream()
                .filter(this::isExclusionInProject)
                .collect(toList());
    }

    private boolean isExclusionInProject(Exclusion exclusion) {
        String modelId = exclusion.getLocation("").getSource().getModelId();
        return projectModelId.equals(modelId);
    }

    private void logViolations(String name, Map<Coordinates, List<Coordinates>> violations, Consumer<String> logger) {
        logger.accept(name + " defines following unnecessary excludes");
        violations.forEach((dependency, invalidExclusions) -> {
            logger.accept("    " + dependency);
            invalidExclusions.forEach(invalidExclusion -> logger.accept("        - " + invalidExclusion));
        });
    }

    private Set<Coordinates> collectActualCoordinates(Coordinates currentCoordinates)
            throws DependencyGraphBuilderException {
        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setProject(createProjectForDependency(currentCoordinates));

        DependencyNode dependencyGraph = dependencyGraphBuilder.buildDependencyGraph(buildingRequest, null);

        Deque<DependencyNode> nodes = new ArrayDeque<>(dependencyGraph.getChildren());
        Set<Coordinates> actualCoordinates = new java.util.HashSet<>();
        while (!nodes.isEmpty()) {
            DependencyNode node = nodes.removeFirst();
            actualCoordinates.add(coordinates(
                    node.getArtifact().getGroupId(), node.getArtifact().getArtifactId()));
            nodes.addAll(node.getChildren());
        }

        return actualCoordinates;
    }

    private MavenProject createProjectForDependency(Coordinates currentCoordinates) {
        Model model = project.getModel().clone();
        model.setDependencies(
                Collections.singletonList(copyDependencyWithoutExclusions(currentCoordinates.getDependency())));
        model.setDependencyManagement(copyDependencyManagementWithoutExclusions(currentCoordinates));

        MavenProject projectForAnalysis = new MavenProject(model);
        projectForAnalysis.setArtifact(project.getArtifact());
        projectForAnalysis.setFile(project.getFile());
        projectForAnalysis.setParent(project.getParent());
        projectForAnalysis.setProjectBuildingRequest(project.getProjectBuildingRequest());

        return projectForAnalysis;
    }

    private Dependency copyDependencyWithoutExclusions(Dependency dependency) {
        Dependency copiedDependency = dependency.clone();
        copiedDependency.setExclusions(Collections.emptyList());
        return copiedDependency;
    }

    private DependencyManagement copyDependencyManagementWithoutExclusions(Coordinates currentCoordinates) {
        DependencyManagement dependencyManagement = project.getDependencyManagement();
        if (dependencyManagement == null) {
            return null;
        }

        DependencyManagement copiedDependencyManagement = dependencyManagement.clone();
        copiedDependencyManagement.setDependencies(copiedDependencyManagement.getDependencies().stream()
                .map(dependency -> sameArtifact(currentCoordinates, dependency)
                        ? copyDependencyWithoutExclusions(dependency)
                        : dependency.clone())
                .collect(toList()));
        return copiedDependencyManagement;
    }

    private boolean sameArtifact(Coordinates currentCoordinates, Dependency dependency) {
        return currentCoordinates.getGroupId().equals(dependency.getGroupId())
                && currentCoordinates.getArtifactId().equals(dependency.getArtifactId());
    }
}
