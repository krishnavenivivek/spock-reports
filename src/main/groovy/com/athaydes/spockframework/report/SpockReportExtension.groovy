package com.athaydes.spockframework.report

import com.athaydes.spockframework.report.internal.ConfigLoader
import com.athaydes.spockframework.report.internal.EmptyInitializationException
import com.athaydes.spockframework.report.internal.FeatureRun
import com.athaydes.spockframework.report.internal.SpecData
import com.athaydes.spockframework.report.internal.SpecInitializationError
import com.athaydes.spockframework.report.internal.SpecProblem
import com.athaydes.spockframework.report.util.Utils
import groovy.util.logging.Slf4j
import org.spockframework.runtime.IRunListener
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.model.ErrorInfo
import org.spockframework.runtime.model.FeatureInfo
import org.spockframework.runtime.model.IterationInfo
import org.spockframework.runtime.model.SpecInfo
import org.spockframework.util.Nullable

import java.util.concurrent.atomic.AtomicBoolean

/**
 *
 * User: Renato
 */
@Slf4j
class SpockReportExtension implements IGlobalExtension {

    static final PROJECT_URL = 'https://github.com/renatoathaydes/spock-reports'

    private final AtomicBoolean initialized = new AtomicBoolean( false )
    protected ConfigLoader configLoader = new ConfigLoader()

    IReportCreator reportCreator

    @Override
    void start() {
        if ( !initialized.getAndSet( true ) ) {
            log.debug "Configuring ${this.class.name}"
            def config = configLoader.loadConfig()

            String reportCreatorClassName = config.remove( IReportCreator.name )

            try {
                reportCreator = instantiateReportCreator( reportCreatorClassName )
                configLoader.apply( reportCreator, config )
            } catch ( e ) {
                log.warn( "Failed to create instance of $reportCreatorClassName", e )
            }
        }
    }

    @Override
    void stop() {
        reportCreator?.done()
    }

    @Override
    void visitSpec( SpecInfo specInfo ) {
        if ( reportCreator != null ) {
            specInfo.addListener createListener()
        } else {
            log.warn "Not creating report for ${specInfo.name} as reportCreator is null"
        }
    }

    IReportCreator instantiateReportCreator( String reportCreatorClassName ) {
        def reportCreatorClass = Class.forName( reportCreatorClassName )
        reportCreatorClass
                .asSubclass( IReportCreator )
                .newInstance()
    }

    // this method is patched by the UseTemplateReportCreator category and others
    SpecInfoListener createListener() {
        new SpecInfoListener( reportCreator )
    }

}

@Slf4j
class SpecInfoListener implements IRunListener {

    final IReportCreator reportCreator
    @Nullable
    SpecData specData
    @Nullable
    IterationInfo currentIteration
    long startT

    SpecInfoListener( IReportCreator reportCreator ) {
        this.reportCreator = reportCreator
    }

    @Override
    synchronized void beforeSpec( SpecInfo spec ) {
        specData = new SpecData( info: spec )
        log.debug( "Before spec: {}", Utils.getSpecClassName( specData ) )
        startT = System.currentTimeMillis()
    }

    @Override
    void beforeFeature( FeatureInfo feature ) {
        log.debug( "Before feature: {}", feature.name )
        specData.featureRuns << new FeatureRun( feature: feature )
    }

    @Override
    void beforeIteration( IterationInfo iteration ) {
        log.debug( "Before iteration: {}", iteration.name )
        currentRun().failuresByIteration[ iteration ] = [ ]
        currentIteration = iteration
    }

    @Override
    void afterIteration( IterationInfo iteration ) {
        log.debug( "After iteration: {}", iteration.name )
        currentIteration = null
    }

    @Override
    void afterFeature( FeatureInfo feature ) {
        log.debug( "After feature: {}", feature.name )
    }

    @Override
    void afterSpec( SpecInfo spec ) {
        assert specData.info == spec
        log.debug( "After spec: {}", Utils.getSpecClassName( specData ) )
        specData.totalTime = System.currentTimeMillis() - startT
        reportCreator.createReportFor specData
        specData = null
    }

    @Override
    void error( ErrorInfo errorInfo ) {
        try {
            def errorInInitialization = ( specData == null )
            log.debug( "Error on spec: {}", errorInInitialization ?
                    "<${EmptyInitializationException.INIT_ERROR}>" :
                    Utils.getSpecClassName( specData ) )

            if ( errorInInitialization ) {
                // call beforeSpec because Spock does not do it in this case
                def specInfo = errorInfo.method.parent
                beforeSpec specInfo

                def currentError = new ErrorInfo( errorInfo.method, new SpecInitializationError( errorInfo.exception ) )
                def features = specInfo.allFeaturesInExecutionOrder

                // simulate all features failing
                if ( features ) for ( featureInfo in features ) {
                    markWithInitializationError featureInfo
                    beforeFeature featureInfo
                    error currentError
                    afterFeature featureInfo

                    // only the first error needs to be complete, use a dummy error for the next features
                    currentError = new ErrorInfo( errorInfo.method, EmptyInitializationException.instance )
                } else {
                    // the error occurred before even the features could be initialized,
                    // make up a fake failed feature to show in the report
                    specData.initializationError = currentError
                    def featureInfo = dummyFeature()
                    beforeFeature featureInfo
                    error currentError
                    afterFeature featureInfo
                }

                // Spock will not call afterSpec in this case as of version 1.0-groovy-2.4
                afterSpec specData.info
            } else {
                def iteration = currentIteration ?: dummySpecIteration()
                currentRun().failuresByIteration[ iteration ] << new SpecProblem( errorInfo )
            }
        } catch ( Throwable e ) {
            // nothing we can do here
            e.printStackTrace()
        }
    }

    @Override
    void specSkipped( SpecInfo spec ) {
        // specInfo already knows if it's skipped
    }

    @Override
    void featureSkipped( FeatureInfo feature ) {
        // feature already knows if it's skipped
    }

    private FeatureRun currentRun() {
        if ( specData.featureRuns.empty ) {
            specData.featureRuns.add new FeatureRun( feature: specData.info.features?.first() ?: dummyFeature() )
        }
        specData.featureRuns.last()
    }

    private void markWithInitializationError( FeatureInfo featureInfo ) {
        def originalGetName = featureInfo.&getName
        featureInfo.metaClass.getName = { "[${EmptyInitializationException.INIT_ERROR}] ${originalGetName()}" }
    }

    private IterationInfo dummySpecIteration() {
        def currentRun = currentRun()
        def iteration = new IterationInfo( currentRun.feature, [ ] as Object[], 1 )
        iteration.name = '<No Iteration!>'
        currentRun.failuresByIteration.put( iteration, [ ] )
        iteration
    }

    private static FeatureInfo dummyFeature() {
        new FeatureInfo( name: '<No Feature initialized!>' )
    }

}
