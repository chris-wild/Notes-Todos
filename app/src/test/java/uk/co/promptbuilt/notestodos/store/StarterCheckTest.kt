package uk.co.promptbuilt.notestodos.store

import org.junit.Assert.assertEquals
import org.junit.Test

class StarterCheckTest {
    // The same token and value are pinned in backend/ops/test/starter.test.js: the Worker
    // refuses an integrity token whose nonce differs, so the two must never drift apart.
    @Test
    fun nonceMatchesTheWorker() {
        assertEquals(
            "kQDtv7xWYJgVMr2kNTBf6932c9-FHq5YWshJBNSARAQ=",
            StarterCheck.nonce("1B4E28BA-2FA1-41D2-883F-0016D3CCA427"),
        )
    }
}
