/*
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Copyright Contributors to the Zowe Project.
 */

package org.zowe.jenkins_shared_library.pipelines.base

import groovy.util.logging.Log
import java.util.concurrent.TimeUnit
import org.codehaus.groovy.runtime.InvokerHelper
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.zowe.jenkins_shared_library.email.Email
import org.zowe.jenkins_shared_library.pipelines.base.arguments.*
import org.zowe.jenkins_shared_library.pipelines.base.enums.ResultEnum
import org.zowe.jenkins_shared_library.pipelines.base.enums.StageStatus
import org.zowe.jenkins_shared_library.pipelines.base.exceptions.*
import org.zowe.jenkins_shared_library.pipelines.base.models.*
import org.zowe.jenkins_shared_library.pipelines.Build
import org.zowe.jenkins_shared_library.pipelines.Constants

/**
 * This class represents a basic Jenkins pipeline. Use the methods of this class to add stages to
 * your pipeline.
 *
 * <dl><dt><b>Required Plugins:</b></dt><dd>
 * <p>The following plugins are required:
 *
 * <ul>
 *     <li><a href="https://plugins.jenkins.io/workflow-support">Pipeline: Supporting APIs</a></li>
 *     <li><a href="https://plugins.jenkins.io/pipeline-stage-step">Pipeline: Stage Step</a></li>
 *     <li><a href="https://plugins.jenkins.io/workflow-basic-steps">Pipeline: Basic Steps</a></li>
 *     <li><a href="https://plugins.jenkins.io/email-ext">Email Extension</a></li>
 *     <li><a href="https://plugins.jenkins.io/workflow-scm-step">Pipeline: SCM Step</a></li>
 *     <li><a href="https://plugins.jenkins.io/workflow-durable-task-step">Pipeline: Nodes and Processes</a></li>
 * </ul>
 * </p>
 * </dd></dl>
 *
 * @Setup
 * <ul>
 *     <li>Create a {@code Multibranch Pipeline} or {@code Pipeline} job for your project. Any other
 *     type of build will fail.</li>
 * </ul>
 *
 *
 * @Example
 *
 * <pre>
 * {@literal @}Library('fill this out according to your setup') import org.zowe.jenkins_shared_library.pipelines.base.Pipeline
 *
 * node('pipeline-node') {
 *   Pipeline pipeline = new Pipeline(this)
 *
 *   // Set your config up before calling setup
 *   pipeline.admins.add("userid1", "userid2", "userid3")
 *
 *   // update branches settings if we have different settings from #defineDefaultBranches()
 *   pipeline.branches.addMap([
 *     [
 *       name         : 'lts-incremental',
 *       isProtected  : true,
 *       buildHistory : 20,
 *     ]
 *   ])
 *
 *   // MUST BE CALLED FIRST
 *   pipeline.setup()
 *
 *   // Create a stage in your pipeline
 *   pipeline.createStage(name: 'Some Pipeline Stage', stage: {
 *       echo "This is my stage"
 *   })
 *
 *   // MUST BE CALLED LAST
 *   pipeline.end()
 * }
 * </pre>
 *
 * <p>In the above example, the stages will run on a node labeled {@code 'pipeline-node'}. You
 * must define the node where your pipeline will execute.</p>
 *
 * <p>Stages are not executed until the {@link #end} method is called. This means that you can't
 * rely on stages being executed as soon as they are defined. If you need logic to help determine if
 * a stage should be executed, you must use the proper options allowed by {@link StageArguments}.</p>
 *
 * @Note Due to issues with how Jenkins loads a shared pipeline library, you might notice
 * that some methods that were meant to be overridden by subclasses are just named differently. This
 * is due to how the CPS Jenkins wrapper modifies classes and subclasses. See below for the full
 * explanation of the problem.</p>
 *
 * <ul>
 *     <li>The superclass implements a method of the signature {@code setup()}</li>
 *     <li>The subclass implements a method of the signature {@code setup()}. Which is the same as the
 *     superclass, so under normal OOP the method would be overridden.</li>
 *     <li>In the subclass's implementation of {@code setup()}, it calls {@code super.setup()}.<ul>
 *             <li>In normal Groovy OOP, this would be fine and the superclass method will be called
 *             properly.</li>
 *             <li>In Jenkins, calling {@code super.setup()} actually calls the subclass method.
 *             this results in a stack overflow error, since the method is just calling itself until
 *             the method stack is completely used up.</li>
 *         </ul>
 *     </li>
 * </ul>
 *
 * <p>I believe this is due to how Jenkins does inheritance. It looks like Jenkins is just taking
 * all the methods available across the superclasses of a class and adding them as a base
 * definition to the class itself. When there's a conflict, it seems that Jenkins just takes the
 * method from the lowest class in the hierarchy and refers all calls to that method no matter what.
 * This theory stems from the fact that if you call a superclass method that accesses a superclass
 * private variable, Jenkins complains that the variable doesn't exist. If you change that variable
 * or method to protected or public, Jenkins doesn't complain anymore and operation acts as normal.
 * </p>
 *
 * <p>This <a href="https://issues.jenkins-ci.org/browse/JENKINS-47355?jql=text%20~%20%22inherit%20class%22">issue</a>
 * seems to signify that they may never fix this problem.</p>
 *
 * @see jenkins_shared_library.behavior.CPSGrandchild behavior.CPSGrandchild
 * @see jenkins_shared_library.behavior.NonCPSGrandchild behavior.NonCPSGrandchild
 */
@Log
class Pipeline {
    /**
     * The name of the setup stage.
     *
     * @Default {@code "Setup"}.
     */
    protected static final String _SETUP_STAGE_NAME = "Setup"

    Build build

    /**
     * Package name of the project
     *
     * @Note This property could be used for different purpose based on pipeline. For GenericPipeline,
     * this property is used to generate artifact publishing path in the artifactory. If we have package
     * name of {@code org.zowe.my-project}, the artifacts generated by this project will be placed
     * into {@code &lt;repo&gt;/org/zowe/my-project/} folder.
     *
     * @see jenkins_shared_library.pipelines.generic.GenericPipeline#getBuildStringMacros(Map&lt;String, String&gt;)
     */
    String packageName = ''

    /**
     * Package current semantic version in {@code &lt;major&gt;.&lt;minor&gt;.&lt;patch&gt;} format.
     *
     * @Note This version should NOT have {@code "v"} prefix, and should NOT have pre-release string
     * and metadata.
     */
    String version

    /**
     * Base directory of the project.
     *
     * <p>By assigning a value to baseDirectory, many built-in stages will be wrapped with
     * <pre>dir(baseDirectory) {}</pre></p>.
     */
    String baseDirectory = ''

    /**
     * This is a list of administrator ids that will receive notifications when a build
     * happens on a protected branch.
     */
    final PipelineAdmins admins = new PipelineAdmins()

    /**
     * A map of branches.
     */
    Branches<Branch> branches = new Branches<Branch>(Branch.class)

    /**
     * This control variable represents internal states of items in the pipeline.
     */
    protected PipelineControl _control = new PipelineControl()

    /**
     * Tracks if the current branch is protected.
     */
    protected boolean _isProtectedBranch = false

    /**
     * Tracks if the remaining stages should be skipped.
     */
    protected boolean _shouldSkipRemainingStages = false

    /**
     * The stages of the pipeline to execute. As stages are created, they are
     * added into this control class.
     */
    protected final Stages _stages = new Stages()

    /**
     * An Email instance to handle email related functions
     */
    protected final Email _email

    /**
     * Reference to the groovy pipeline variable.
     *
     * @see #Pipeline(def)
     */
    def steps

    /**
     * Options that are to be added to the build.
     */
    List buildOptions = []

    /**
     * Build parameters that will be defined to the build
     */
    List buildParameters = []

    /**
     * Upstream builds
     */
    List<String> buildUpstreams = []

    /**
     * Gets the stage skip parameter name.
     *
     * @param stage The stage to skip.
     * @return The name of the skip stage parameter.
     */
    protected static String _getStageSkipOption(Stage stage) {
        return "Skip Stage: ${stage.name}"
    }

    /**
     * Constructs the class.
     *
     * <p>When invoking from a Jenkins pipeline script, the Pipeline must be passed
     * the current environment of the Jenkinsfile to have access to the steps.</p>
     *
     * @Example
     * <pre>
     * def pipeline = new Pipeline(this)
     * </pre>
     *
     * @param steps The workflow steps object provided by the Jenkins pipeline
     */
    Pipeline(steps) {
        this.steps = steps

        this._email = new Email(steps)
        // moved to here so it will always be initialized, even if the pipeline doesn't have setup...
        this.build = new Build(steps.currentBuild)
    }

    /**
     * Setup default branch settings
     *
     * @Note To overwrite this default settings, you can re-define with same name:
     * <pre>
     *   pipeline.branches.addMap([
     *     [
     *       name         : 'master',
     *       isProtected  : false,
     *       buildHistory : 10,
     *     ]
     *   ])
     * </pre>
     */
    protected void defineDefaultBranches() {
        branches.addMap([
            [
                name               : 'master',
                isProtected        : true,
                buildHistory       : 20,
            ],
            [
                name               : 'v[0-9]+\\.[0-9x]+(\\.[0-9x]+)?/master',
                isProtected        : true,
                buildHistory       : 20,
            ],
        ])
    }

    /**
     * Add new build options to the pipeline.
     *
     * <p>If you need custome build options, you can use this method to notify pipeline.</p>
     *
     * @Note Use {@code properties()} directly in your pipeline will not work because the library
     * may oeverwrite your settings.
     *
     * @Example You want to define thottle for your pipeline:
     * <pre>
     * def thottle = [
     *     $class           : 'JobPropertyImpl',
     *     throttle         : [
     *         count        : 1,
     *         durationName : 'hour',
     *         userBoost    : true
     *     ]
     * ]
     * pipeline.addBuildOption(thottle)
     * </pre>
     *
     * @param option           build option
     */
    void addBuildOption(def option) {
        buildOptions.push(option)
    }

    /**
     * Add new build parameter to the pipeline
     *
     * @Note If you define custome build parameters directly, need to use these methods to notify
     * library. Otherwise your definition will be overwritten by the pipeline definitions.
     *
     * @Example You manually defined a build parameter in your pipeline,
     * <pre>
     * def myBuildParamA = string(
     *     name: 'param_a',
     *     description: 'My custom build parameter A',
     *     defaultValue: 'default-value-of-a',
     *     trim: true,
     *     required: true
     * )
     * // this line is not neccessary
     * properties(parameters([myBuildParamA]))
     * </pre>
     *
     * <p>Then you may need to notify the pipeline with this code:</p>
     * <pre>
     * // this line is required when you use Pipeline library
     * pipeline.addBuildParameter(myBuildParamA)
     * </pre>
     *
     * @param param           build parameter
     */
    void addBuildParameter(def param) {
        buildParameters.push(param)
    }

    /**
     * Add new build parameters to the pipeline
     *
     * <p>This is a bulk way to add more than one build parameters.
     *
     * @Example
     * <pre>
     * pipeline.addBuildParameters([
     *     string(
     *         name: 'param_a',
     *         description: 'My custom build parameter A',
     *         defaultValue: 'default-value-of-a',
     *         trim: true,
     *         required: true
     *     ),
     *     string(
     *         name: 'param_b',
     *         description: 'My custom build parameter B',
     *         defaultValue: 'default-value-of-b',
     *         trim: true,
     *         required: true
     *     )
     * ])
     * </pre>
     *
     * @see #addBuildParameter(def)
     * @param params           list of build parameters
     */
    void addBuildParameters(List params) {
        params.each{ param ->
            buildParameters.push(param)
        }
    }

    /**
     * Add new build parameters to the pipeline.
     *
     * <p>This is a bulk way to add more than one build parameters.
     *
     * @Example
     * <pre>
     * pipeline.addBuildParameters(string(
     *     name: 'param_a',
     *     description: 'My custom build parameter A',
     *     defaultValue: 'default-value-of-a',
     *     trim: true,
     *     required: true
     * ), string(
     *     name: 'param_b',
     *     description: 'My custom build parameter B',
     *     defaultValue: 'default-value-of-b',
     *     trim: true,
     *     required: true
     * ))
     * </pre>
     *
     * @see #addBuildParameter(def)
     * @param params           list of build parameters
     */
    void addBuildParameters(UninstantiatedDescribable... params) {
        params.each{ param ->
            buildParameters.push(param)
        }
    }

    /**
     * Define upstreams of the pipeline.
     *
     * @Example
     * <pre>
     * pipeline.addUpstreams('/Explorer-Data Sets/master', '/Explorer-Jobs/master')
     * </pre>
     *
     * @param upstreams    all upstreams
     */
    void addUpstreams(String... upstreams) {
        for (String upstream : upstreams) {
            buildUpstreams.push(upstream)
        }
    }

    /**
     * Creates a new stage to be run in the Jenkins pipeline.
     *
     * <p>Stages are executed in the order that they are created. For more details on what arguments
     * can be sent into a stage, see the {@link jenkins_shared_library.pipelines.base.arguments.StageArguments} class.</p>
     *
     * <p>Stages can also encounter various conditions that will cause them to skip. The following
     * skip search order is used when determining if a stage should be skipped.</p>
     *
     * <ol>
     * <li>If the {@link jenkins_shared_library.pipelines.base.arguments.StageArguments#resultThreshold} value is greater than the current result, the
     * stage will be skipped. There is no override for this operation.</li>
     * <li>If the stage is skippable and the stage skip build option was passed, the stage will
     * be skipped.</li>
     * <li>If the remaining pipeline stages are to be skipped, then this stage will be skipped. This
     * can be overridden if the stage has set {@link jenkins_shared_library.pipelines.base.arguments.StageArguments#doesIgnoreSkipAll} to true.</li>
     * <li>Finally, if the call to {@link jenkins_shared_library.pipelines.base.arguments.StageArguments#shouldExecute} returns false, the stage will be
     * skipped.</li>
     * </ol>
     *
     * <p>If the stage is not skipped after executing the above checks, the stage will continue to
     * its execute phase.</p>
     *
     * @param args The arguments that define the stage.
     * @return The created {@link Stage}.
     */
    final Stage createStage(StageArguments args) {
        // @FUTURE allow easy way for create stage to specify build parameters
        Stage stage = new Stage(args: args, name: args.name, order: _stages.size() + 1)
        String stageSkipOption = _getStageSkipOption(stage)

        _stages.add(stage)

        if (args.isSkippable) {
            // Add the option to the build, this will be called in setup
            buildParameters.push(
                    steps.booleanParam(
                            defaultValue: false,
                            description: "Setting this to true will skip the stage \"${args.name}\"",
                            name: stageSkipOption
                    )
            )
        }

        stage.execute = {
            steps.stage(args.name) {
                steps.timeout(time: args.timeout.time, unit: args.timeout.unit) {
                    stage.status = StageStatus.EXECUTE

                    // Skips the stage when called with a reason code
                    Closure skipStage = { reason ->
                        steps.echo "Stage Skipped: \"${args.name}\" Reason: ${reason}"
                        stage.status = StageStatus.SKIP
                        Utils.markStageSkippedForConditional(args.name)
                    }

                    // If the stage is skippable
                    if (stage.args.isSkippable) {
                        // Check if the stage was skipped by the build parameter
                        if (steps.params.containsKey(stageSkipOption)) {
                            stage.isSkippedByParam = steps.params[stageSkipOption]
                        }
                    }

                    _closureWrapper(stage) {
                        // First check that setup was called first
                        if ((!_control.setup || _control.setup.status < StageStatus.SUCCESS) && stage.name != _SETUP_STAGE_NAME) {
                            throw new StageException(
                                    "Pipeline setup not complete, please execute setup() on the instantiated Pipeline class",
                                    args.name
                            )
                        } else if (!steps.currentBuild.resultIsBetterOrEqualTo(args.resultThreshold.value)) {
                            skipStage("${steps.currentBuild.currentResult} does not meet required threshold ${args.resultThreshold.value}")
                        } else if (stage.isSkippedByParam) {
                            skipStage("Skipped by build parameter")
                        } else if (!args.doesIgnoreSkipAll && _shouldSkipRemainingStages) {
                            // If doesIgnoreSkipAll is true then this check is ignored, all others are not though
                            skipStage("All remaining steps are skipped")
                        } else if (!args.shouldExecute()) {
                            skipStage("Stage was not executed due to shouldExecute returning false")
                        }
                        // Run the stage
                        else {
                            steps.echo "Executing stage ${args.name}"

                            if (args.isSkippable) {
                                steps.echo "This step can be skipped by setting the `${stageSkipOption}` option to true"
                            }

                            def environment = []

                            // Add items to the environment if needed
                            if (args.environment) {
                                args.environment.each { key, value -> environment.push("${key}=${value}") }
                            }

                            // Run the passed stage with the proper environment variables
                            steps.withEnv(environment) {
                                _closureWrapper(stage) {
                                    // FIXME: how to do this properly?
                                    if (args.displayAnsiColor) {
                                        if (args.displayTimestamp) {
                                            if (args.baseDirectory) {
                                                steps.dir(args.baseDirectory) {
                                                    steps.timestamps {
                                                        steps.ansiColor('xterm') {
                                                            args.stage(stage.name)
                                                        }
                                                    }
                                                }
                                            } else {
                                                steps.timestamps {
                                                    steps.ansiColor('xterm') {
                                                        args.stage(stage.name)
                                                    }
                                                }
                                            }
                                        } else {
                                            if (args.baseDirectory) {
                                                steps.dir(args.baseDirectory) {
                                                    steps.ansiColor('xterm') {
                                                        args.stage(stage.name)
                                                    }
                                                }
                                            } else {
                                                steps.ansiColor('xterm') {
                                                    args.stage(stage.name)
                                                }
                                            }
                                        }
                                    } else {
                                        if (args.displayTimestamp) {
                                            if (args.baseDirectory) {
                                                steps.dir(args.baseDirectory) {
                                                    steps.timestamps {
                                                        args.stage(stage.name)
                                                    }
                                                }
                                            } else {
                                                steps.timestamps {
                                                    args.stage(stage.name)
                                                }
                                            }
                                        } else {
                                            if (args.baseDirectory) {
                                                steps.dir(args.baseDirectory) {
                                                    args.stage(stage.name)
                                                }
                                            } else {
                                                args.stage(stage.name)
                                            }
                                        }
                                    }

                                    stage.status = StageStatus.SUCCESS
                                }
                            }
                        }
                    }
                }
            }
        }

        return stage
    }

    /**
     * Creates a new stage to be run in the Jenkins pipeline.
     *
     * @param arguments A map of arguments that can be instantiated to a {@link StageArguments} instance.
     * @return The created {@link Stage}.
     *
     * @see #createStage(StageArguments)
     */
    final Stage createStage(Map arguments) {
        // Call the overloaded method
        // if the Arguments class is not base class, the {@code "arguments as SomeStageArguments"} statement
        // has problem to set values of properties defined in super class.
        StageArguments args = new StageArguments()
        InvokerHelper.setProperties(args, arguments)
        return createStage(args)
    }

    /**
     * Gets the first failing stage within {@link #_stages}
     *
     * @return The first failing stage if one exists, null otherwise
     */
    final Stage getFirstFailingStage() {
        return _stages.firstFailingStage
    }

    /**
     * Get a stage from the available stages by name.
     *
     * @param stageName The name of the stage object to get.
     *
     * @return The stage object for the requested stage.
     */
    final Stage getStage(String stageName) {
        return _stages.getStage(stageName)
    }

    /**
     * Set the build result
     * @param result The new result for the build.
     */
    final void setResult(ResultEnum result) {
        steps.currentBuild.result = result.value
    }

    /**
     * Initialize the pipeline.
     *
     * <p>This method MUST be called before any other stages are created. If not called, your Jenkins
     * pipeline will fail. It is also recommended that any public properties of this class are set
     * prior to calling setup.</p>
     *
     * <p>When extending the Pipeline class, this method must be called in any overridden setup
     * methods. Failure to do so will result in the pipeline indicating that setup was never called.
     * </p>
     *
     * @Stages
     * <p>The setup method creates 2 stages in your Jenkins pipeline using the {@link #createStage(Map)}
     * function.</p>
     *
     * <dl>
     *     <dt><b>Setup</b></dt>
     *     <dd>Used internally to indicate that the Pipeline has been properly setup.</dd>
     *     <dt><b>Checkout</b></dt>
     *     <dd>Checks the source out for the pipeline.</dd>
     * </dl>
     *
     * @Note This method was intended to be called {@code setup} but had to be named
     *       {@code setupBase} due to the issues described in {@link Pipeline}.
     *
     * @param arguments The arguments for the added stages.
     */
    void setupBase(SetupStageArguments arguments) {
        // init package name/version if provided
        if (arguments.packageName) {
            this.packageName = arguments.packageName
        }
        if (arguments.version) {
            this.version = arguments.version
        }
        if (arguments.baseDirectory) {
            this.baseDirectory = arguments.baseDirectory
        }

        // prepare default configurations
        this.defineDefaultBranches()

        // Create the stage and hold the variable for the future
        Stage setup = createStage(
            name: _SETUP_STAGE_NAME,
            stage: {
                steps.echo "Setup was called first"

                if (_stages.firstFailingStage) {
                    if (_stages.firstFailingStage.exception) {
                        throw _stages.firstFailingStage.exception
                    } else {
                        throw new StageException("Setup found a failing stage but there was no associated exception.", _stages.firstFailingStage.name)
                    }
                } else {
                    steps.echo "No problems with pre-initialization of pipeline :)"
                }
            },
            isSkippable: false,
            timeout: arguments.setup
        )

        // Check for duplicate setup call
        if (_control.setup) {
            _stages.firstFailingStage = _control.setup
            _control.setup.exception = new StageException("Setup was called twice!", _control.setup.name)
        } else {
            _control.setup = setup
        }

        // steps.scm could be empty if it's regular pipeline and without using any SCM, like pipeline
        // script is defined inside the job.
        //
        // For regular pipeline pointing to a github repository Jenkinsfile, or multibranch pipelines,
        // scm should exist.
        if (steps.scm && !arguments.skipCheckout) {
            createStage(
                name: 'Checkout',
                stage: {
                    steps.checkout steps.scm
                },
                isSkippable: false,
                timeout: arguments.checkout
            )
        }
    }

    /**
     * Initialize the pipeline.
     *
     * @param arguments A map that can be instantiated as {@link jenkins_shared_library.pipelines.base.arguments.SetupStageArguments}
     * @see #setupBase(SetupStageArguments)
     */
    void setupBase(Map arguments = [:]) {
        SetupStageArguments args = new SetupStageArguments()
        InvokerHelper.setProperties(args, arguments)
        setupBase(args)
    }

    /**
     * Pseudo setup method, should be overridden by inherited classes
     * @param arguments The arguments for the added stages.
     */
    protected void setup(SetupStageArguments arguments) {
        setupBase(arguments)
    }

    /**
     * Pseudo setup method, should be overridden by inherited classes
     * @param arguments A map that can be instantiated as {@link jenkins_shared_library.pipelines.base.arguments.SetupStageArguments}
     */
    protected void setup(Map arguments = [:]) {
        setupBase(arguments)
    }

    /**
     * Signal that no more stages will be added and begin pipeline execution.
     *
     * <p>The end method MUST be the last method called as part of your pipeline. The end method is
     * responsible for executing all the stages previously created after setting the required build
     * options and possible stage parameters. Failure to call this method will prevent your pipeline
     * stages from executing.</p>
     *
     * <p>Prior to executing the stages, various build options are set. Some of these options include
     * the build history and stage skip parameters. After this is done, the method will execute
     * all of the created stages in the order they were defined.</p>
     *
     * <p>After stage execution, desired logs will be captured and an email will be sent out to
     * those that made the commit. If the build failed or returned to normal, all committers since
     * the last successful build will also receive the email. Finally if this build is on a
     * protected branch, all emails listed in the {@link #admins} list will also receive a status
     * email.</p>
     *
     * @Note This method was intended to be called {@code end} but had to be named
     *       {@code endBase} due to the issues described in {@link Pipeline}.
     *
     * @param args Arguments for the end method.
     */
    final void endBase(EndArguments args) {
        // Create this stage so that the pipeline has a place to log all the
        // post build actions. If the build has not thrown an exception or been
        // aborted, the stage should run.
        createStage(
            name: "Complete",
            isSkippable: false,
            doesIgnoreSkipAll: true,
            stage: {
                steps.echo "Pipeline Execution Complete"
            },
            timeout: [time: 10, unit: TimeUnit.SECONDS],
            resultThreshold: ResultEnum.FAILURE
        )

        try {
            // First setup the build properties
            def history = Constants.DEFAULT_BUILD_HISTORY

            if (steps.env.BRANCH_NAME) {
                def branchProperties = branches.getByPattern(steps.env.BRANCH_NAME)
                if (branchProperties) {
                    // Add protected branch to build options
                    if (branchProperties.isProtected) {
                        _isProtectedBranch = true
                    }
                    if (branchProperties.buildHistory) {
                        history = branchProperties.buildHistory
                    } else if (_isProtectedBranch) {
                        history = Constants.DEFAULT_BUILD_HISTORY_FOR_PROTECTED_BRANCH
                    }
                }
            }

            // should we always disable concurrent builds?
            buildOptions.push(steps.disableConcurrentBuilds())

            // Add log rotator to build options
            buildOptions.push(steps.buildDiscarder(steps.logRotator(numToKeepStr: "${history}")))

            // setup upstream
            if (buildUpstreams.size() > 0) {
                buildOptions.push(steps.pipelineTriggers([
                    steps.upstream(
                        threshold        : 'SUCCESS',
                        upstreamProjects : buildUpstreams.join(',')
                    )
                ]))
            }

            // Add any parameters to the build here
            if (buildParameters.size() > 0) {
                buildOptions.push(steps.parameters(buildParameters))
            }

            // update build properties
            steps.properties(buildOptions)

            // debug info
            def stageList = ["These stages are defined:"]
            Stage stage = _stages.getFirstStageToExecute()
            // Loop while we have a stage
            while (stage) {
                // Execute the stage
                stageList.push("- ${stage.name}")
                // Move to the next stage
                stage = stage.next
            }
            stageList.push("Total: ${stageList.size() - 1}")
            log.fine(stageList.join("\n"))

            // Execute the pipeline
            _stages.execute()
        } finally {
            steps.echo "------------------------------------------------------------------------------------------------" +
                       "------------------------------------------------------------------------------------------------"
            steps.echo "POST BUILD ACTIONS"

            if (args.always) {
                args.always()
            }

            // Gather the log folders here
            if (!args.skipEmail) {
                _sendEmailNotification()
            }

            // pause for debugging
            // steps.sleep time: 60, unit: 'MINUTES'
        }
    }

    /**
     * Signal that no more stages will be added and begin pipeline execution.
     *
     * @param arguments A map that can be instantiated as {@link jenkins_shared_library.pipelines.base.arguments.EndArguments}.
     * @see #endBase(EndArguments)
     */
    final void endBase(Map arguments = [:]) {
        EndArguments args = new EndArguments()
        InvokerHelper.setProperties(args, arguments)
        endBase(args)
    }

    /**
     * Pseudo end method, should be overridden by inherited classes
     *
     * @Note This method is added so we have consistency on creating pipeline with different level of
     * Pipeline classes. No matter you use Base Pipeline, GenericPipeline, or GradlePipeline, etc,
     * you have consistency on using {@link #setup(SetupStageArguments)} and {@link #end(EndArguments)} methods.
     *
     * @param args Arguments for the end method.
     */
    protected void end(EndArguments args) {
        endBase(args)
    }

    /**
     * Pseudo end method, should be overridden by inherited classes
     * @param args A map that can be instantiated as {@link jenkins_shared_library.pipelines.base.arguments.EndArguments}.
     */
    protected void end(Map args = [:]) {
        endBase(args)
    }

    /**
     * Wraps a closure function in a try catch.
     *
     * <p>Used internally by {@link #createStage(StageArguments)} to handle errors thrown by timeouts and
     * stage executions.</p>
     *
     * @param stage The stage that is currently executing
     * @param closure The closure function to execute
     */
    protected final void _closureWrapper(Stage stage, Closure closure) {
        try {
            closure()
        } catch (e) {
            _stages.firstFailingStage = stage

            setResult(ResultEnum.FAILURE)
            stage.exception = e
            stage.status = StageStatus.FAIL
            throw e
        } finally {
            stage.endOfStepBuildStatus = steps.currentBuild.currentResult

            // Don't alert of the build status if the stage already has an exception
            if (!stage.exception && steps.currentBuild.resultIsWorseOrEqualTo('UNSTABLE')) {
                // Add the exception of the bad build status
                stage.status = StageStatus.FAIL
                stage.exception = new StageException("Stage exited with a result of UNSTABLE or worse", stage.name)
                _stages.firstFailingStage = stage
            }
        }
    }

    /**
     * Send an email notification about the result of the build to the appropriate users
     */
    protected void _sendEmailNotification() {
        Build currentBuild = new Build(steps.currentBuild)
        String buildStatus = "${steps.currentBuild.currentResult}"
        String emailText = buildStatus

        if (firstFailingStage?.exception instanceof FlowInterruptedException) {
            buildStatus = "${((FlowInterruptedException) firstFailingStage.exception).result}"

            if (buildStatus == "ABORTED") {
                emailText = "Aborted by ${((FlowInterruptedException) firstFailingStage.exception).causes[0]?.user}"
            } else {
                emailText = buildStatus
            }
        }

        if (buildStatus != "NOT_BUILT") {
            steps.echo "Sending email notification..."
            def subject = buildStatus
            def bodyText = "<h3>${steps.env.JOB_NAME}</h3>"
            bodyText += "<p>Build: <b>#${steps.env.BUILD_NUMBER}</b></p>"
            bodyText += "${steps.env.BRANCH_NAME ? "<p>Branch: <b>${steps.env.BRANCH_NAME}</b></p>" : ''}"
            bodyText += "<p>Result: <b>$emailText</b></p>"
            bodyText += "<hr>"
            bodyText += "<p>Check console output at <a href=\"${steps.RUN_DISPLAY_URL}\">${steps.env.JOB_NAME} [${steps.env.BUILD_NUMBER}]</a></p>"

            // add an image reflecting the result
            if (Constants.notificationImages.containsKey(buildStatus) &&
                Constants.notificationImages[buildStatus].size() > 0) {
                def imageList = Constants.notificationImages[buildStatus]
                def imageIndex = Math.abs(new Random().nextInt() % imageList.size())
                bodyText += "<p><img src=\"" + imageList[imageIndex] + "\" width=\"500\"/></p>"
            }

            bodyText += currentBuild.getChangeSummary()
            bodyText += currentBuild.getTestSummary()

            // Add any details of an exception, if encountered
            if (_stages.firstFailingStage?.exception) { // Safe navigation is where the question mark comes from
                bodyText += "<h3>Failure Details</h3>"
                bodyText += "<table>"
                bodyText += "<tr><td style=\"width: 150px\">First Failing Stage:</td><td><b>${_stages.firstFailingStage.name}</b></td></tr>"
                bodyText += "<tr><td>Exception:</td><td>${_stages.firstFailingStage.exception.toString()}</td></tr>"
                bodyText += "<tr><td style=\"vertical-align: top\">Stack:</td>"
                bodyText += "<td style=\"color: red; display: block; max-height: 350px; max-width: 65vw; overflow: auto\">"
                bodyText += "<div style=\"width: max-content; font-family: monospace;\">"
                def stackTrace = _stages.firstFailingStage.exception.getStackTrace()

                for (int i = 0; i < stackTrace.length; i++) {
                    bodyText += "at ${stackTrace[i]}<br/>"
                }

                bodyText += "</div></td></tr>"
                bodyText += "</table>"
            }

            try {
                // send the email
                this._email.send(
                    subjectTag: subject,
                    body: bodyText,
                    to: _isProtectedBranch ? admins.getCCList() : ""
                )
            }
            catch (emailException) {
                steps.echo "Exception encountered while attempting to send email!"
                steps.echo emailException.toString()
                steps.echo emailException.getStackTrace().join("\n")
            }
        }
    }
}
