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

import org.apache.maven.api.plugin.testing.Basedir;
import org.apache.maven.api.plugin.testing.InjectMojo;
import org.apache.maven.api.plugin.testing.MojoTest;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@MojoTest(realRepositorySession = true)
@Basedir("/unit/analyze-exclusions-real-test")
class AnalyzeExclusionsMojoRealRepoTest {

    @Inject
    private Log log;

    @Test
    @InjectMojo(goal = "analyze-exclusions")
    void testDoesNotReportValidSlf4jReload4jExclusion(AnalyzeExclusionsMojo mojo) throws Exception {
        mojo.execute();

        verify(log, never()).warn("reproducer defines following unnecessary excludes");
        verify(log, never()).warn("    org.apache.hadoop:hadoop-client:3.4.3");
    }
}
