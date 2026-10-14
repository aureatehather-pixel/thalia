package com.thalia.app.voicecheckins.schedule

/** The in-memory double, held to the same contract as the real store. */
class InMemoryOccurrenceStoreTest : OccurrenceStoreContractTest() {
    override fun createStore(): OccurrenceStore = InMemoryOccurrenceStore()
}
