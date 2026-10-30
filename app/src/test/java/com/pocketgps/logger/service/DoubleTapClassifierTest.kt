package com.pocketgps.logger.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapClassifierTest {
    @Test fun acceptsTwoImpactsInsideGestureWindow() {
        val classifier = DoubleTapClassifier()
        assertFalse(classifier.onImpact(9.0f, 1_000L))
        assertTrue(classifier.onImpact(9.0f, 1_280L))
    }

    @Test fun rejectsLowMagnitudeMovement() {
        val classifier = DoubleTapClassifier()
        assertFalse(classifier.onImpact(3.0f, 1_000L))
        assertFalse(classifier.onImpact(3.0f, 1_250L))
    }

    @Test fun rejectsImpactsThatAreTooFarApart() {
        val classifier = DoubleTapClassifier()
        assertFalse(classifier.onImpact(9.0f, 1_000L))
        assertFalse(classifier.onImpact(9.0f, 1_700L))
    }

    @Test fun rejectsDuplicateGestureDuringCooldown() {
        val classifier = DoubleTapClassifier()
        assertFalse(classifier.onImpact(9.0f, 1_000L))
        assertTrue(classifier.onImpact(9.0f, 1_250L))
        assertFalse(classifier.onImpact(9.0f, 1_500L))
        assertFalse(classifier.onImpact(9.0f, 1_750L))
    }

    @Test fun rejectsNonFiniteSensorValues() {
        val classifier = DoubleTapClassifier()
        assertFalse(classifier.onImpact(Float.NaN, 1_000L))
        assertFalse(classifier.onImpact(Float.POSITIVE_INFINITY, 1_200L))
    }
}
