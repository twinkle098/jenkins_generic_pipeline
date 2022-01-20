/*
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Copyright IBM Corporation 2019
 */

package org.zowe.jenkins_shared_library.pipelines.gradle.arguments

import org.zowe.jenkins_shared_library.pipelines.base.models.StageTimeout
import org.zowe.jenkins_shared_library.pipelines.generic.arguments.GenericSetupStageArguments

import java.util.concurrent.TimeUnit

/**
 * Arguments available to the
 * {@link jenkins_shared_library.pipelines.Gradle.GradlePipeline#setupGradle(jenkins_shared_library.pipelines.Gradle.arguments.GradleSetupArguments)}
 * method.
 */
class GradleSetupStageArguments extends GenericSetupStageArguments {
}
