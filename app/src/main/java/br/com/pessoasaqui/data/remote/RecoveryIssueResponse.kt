package br.com.pessoasaqui.data.remote

data class RecoveryIssueResponse(
    val recoveryKey: String,
    val targetIdentity: String,
    val expiresAtEpochMs: Long
)
