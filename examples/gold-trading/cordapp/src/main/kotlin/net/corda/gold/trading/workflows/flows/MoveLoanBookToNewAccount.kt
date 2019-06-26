package net.corda.gold.trading.workflows.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.accounts.contracts.states.AccountInfo
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount
import com.r3.corda.lib.accounts.workflows.internal.accountService
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.*
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.gold.trading.contracts.LoanBookContract
import net.corda.gold.trading.contracts.states.LoanBook
import java.util.*
import java.util.concurrent.atomic.AtomicReference


@StartableByRPC
@StartableByService
@InitiatingFlow
class MoveLoanBookToNewAccount(
        private val accountIdToMoveTo: UUID,
        private val loanBook: StateAndRef<LoanBook>,
        private val carbonCopyReceivers: Collection<AccountInfo>?
) : FlowLogic<StateAndRef<LoanBook>>() {

    constructor(accountId: UUID, loanBook: StateAndRef<LoanBook>) : this(accountId, loanBook, null)

    @Suspendable
    override fun call(): StateAndRef<LoanBook> {
        val currentHoldingAccount = loanBook.state.data.owningAccount?.let { accountService.accountInfo(it) }
        val accountInfoToMoveTo = accountService.accountInfo(accountIdToMoveTo)

        if (accountInfoToMoveTo == null) {
            throw IllegalStateException()
        } else if (currentHoldingAccount == null && loanBook.state.data.owningAccount != null)
            throw IllegalStateException("Attempting to move a loan book from an account we do not know about")
        else {
            val keyToMoveTo = subFlow(RequestKeyForAccount(accountInfoToMoveTo.state.data)).owningKey
            val transactionBuilder = TransactionBuilder(loanBook.state.notary)
                    .addInputState(loanBook)
                    .addOutputState(loanBook.state.data.copy(owningAccount = keyToMoveTo))
                    .addCommand(LoanBookContract.TRANSFER_TO_ACCOUNT, listOfNotNull(keyToMoveTo, loanBook.state.data.owningAccount))
            val locallySignedTx = serviceHub.signInitialTransaction(transactionBuilder, loanBook.state.data.owningAccount!!)
            val sessionForAccountToSendTo = initiateFlow(accountInfoToMoveTo.state.data.host)
            val accountToMoveToSignature = subFlow(CollectSignatureFlow(locallySignedTx, sessionForAccountToSendTo, keyToMoveTo))
            val signedByCounterParty = locallySignedTx.withAdditionalSignatures(accountToMoveToSignature)
            val fullySignedTx = subFlow(FinalityFlow(signedByCounterParty, listOf(sessionForAccountToSendTo).filter { it.counterparty != ourIdentity }))
            val movedState = fullySignedTx.coreTransaction.outRefsOfType(
                    LoanBook::class.java
            ).single()
            // broadcasting the state to carbon copy receivers
            if (currentHoldingAccount != null) {
                subFlow(BroadcastToCarbonCopyReceiversFlow(currentHoldingAccount.state.data, movedState, carbonCopyReceivers))
            }
            return movedState
        }
    }
}

@InitiatedBy(MoveLoanBookToNewAccount::class)
class AccountSigningResponder(val otherSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val accountMovedTo = AtomicReference<AccountInfo>()
        val transactionSigner = object : SignTransactionFlow(otherSession) {
            override fun checkTransaction(tx: SignedTransaction) {
                val keyStateMovedTo = tx.coreTransaction.outRefsOfType(LoanBook::class.java).first().state.data.owningAccount
                keyStateMovedTo?.let {
                    accountMovedTo.set(accountService.accountInfo(keyStateMovedTo)?.state?.data)
                }

                if (accountMovedTo.get() == null) {
                    throw IllegalStateException("Account to move to was not found on this node")
                }

            }
        }
        val transaction = subFlow(transactionSigner)
        if (otherSession.counterparty != serviceHub.myInfo.legalIdentities.first()) {
            val recievedTx = subFlow(
                    ReceiveFinalityFlow(
                            otherSession,
                            expectedTxId = transaction.id,
                            statesToRecord = StatesToRecord.ALL_VISIBLE
                    )
            )
            val accountInfo = accountMovedTo.get()
            if (accountInfo != null) {
                subFlow(BroadcastToCarbonCopyReceiversFlow(accountInfo, recievedTx.coreTransaction.outRefsOfType(LoanBook::class.java).first()))
            }
        }
    }
}


