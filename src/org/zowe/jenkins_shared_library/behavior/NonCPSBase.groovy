/*
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Copyright IBM Corporation 2019
 */

package org.zowe.jenkins_shared_library.behavior

import com.cloudbees.groovy.cps.NonCPS

/**
 * Base class.
 *
 * @see NonCPSGrandchild
 */
class NonCPSBase {
    def steps

    NonCPSBase(steps) {
        steps.echo "NonCPSBase construction"
        this.steps = steps
    }

    void test() {
        steps.echo "NonCPSBase.test()"
    }
}
