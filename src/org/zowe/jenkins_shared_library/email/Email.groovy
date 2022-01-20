/*
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Copyright IBM Corporation 2019
 */

package org.zowe.jenkins_shared_library.email

import org.codehaus.groovy.runtime.InvokerHelper

/**
 * Class to send email notifications.
 *
 * @Example
 * <pre>
 *     def m = new Email(this)
 *     m.send(
 *         subjectTag   : 'FAILURE',
 *         to:          : 'also-to-me@gmail.com',
 *         body         : '&lt;p&gt;Test failed!&lt;/p&gt;',
 *         addProviders : true
 *     )
 * </pre>
 */
class Email {
    /**
     * Reference to the groovy pipeline variable.
     */
    def steps

    /**
     * Constructs the class.
     *
     * <p>When invoking from a Jenkins pipeline script, the Pipeline must be passed
     * the current environment of the Jenkinsfile to have access to the steps.</p>
     *
     * @Example
     * <pre>
     * def o = new Email(this)
     * </pre>
     *
     * @param steps    The workflow steps object provided by the Jenkins pipeline
     */
    Email(steps) {
        this.steps = steps
    }

    /**
     * Send an email.
     *
     * <p>The email will contain {@code [args.subjectTag]} as the first string content followed by the
     * job name and build number</p>
     *
     * @param args Arguments available to the email command.
     */
    void send(EmailArguments args) {
        def subject = "[$args.subjectTag] Job '${steps.env.JOB_NAME} [${steps.env.BUILD_NUMBER}]'"

        steps.echo "Sending Email\n" +
                   "Subject: $subject\n" +
                   "Body:\n${args.body}"

        // send the email
        steps.emailext(
            subject            : subject,
            to                 : args.to,
            body               : args.body,
            mimeType           : args.html ? "text/html" : "text/plain",
            attachLog          : args.attachLog,
            recipientProviders : args.addProviders ? [[$class: 'DevelopersRecipientProvider'],
                                                     [$class: 'UpstreamComitterRecipientProvider'],
                                                     [$class: 'CulpritsRecipientProvider'],
                                                     [$class: 'RequesterRecipientProvider']] : []
        )
    }

    /**
     * Send an email.
     *
     * @param arguments A map that can be instantiated as {@link EmailArguments}.
     * @see #send(EmailArguments)
     */
    void send(Map arguments) {
        // if the Arguments class is not base class, the {@code "arguments as SomeStageArguments"} statement
        // has problem to set values of properties defined in super class.
        EmailArguments args = new EmailArguments()
        InvokerHelper.setProperties(args, arguments)
        send(args)
    }
}
