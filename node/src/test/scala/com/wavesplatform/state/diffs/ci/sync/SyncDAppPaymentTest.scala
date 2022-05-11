package com.wavesplatform.state.diffs.ci.sync

import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.features.BlockchainFeatures.RideV6
import com.wavesplatform.lang.directives.values.V5
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.compiler.TestCompiler
import com.wavesplatform.state.Portfolio
import com.wavesplatform.state.diffs.{ENOUGH_AMT, produceRejectOrFailedDiff}
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.{Asset, TxHelpers}

class SyncDAppPaymentTest extends PropSpec with WithDomain {

  property("negative sync dApp payments amount rejects tx after enforceTransferValidationAfter") {
    for {
      bigComplexityDApp1 <- Seq(false, true)
      bigComplexityDApp2 <- Seq(false, true)
      customAsset        <- Seq(false, true)
    } {
      val (balances, preparingTxs, invoke, dApp1, dApp2, asset) = scenario(bigComplexityDApp1, bigComplexityDApp2, customAsset, -1)
      withDomain(RideV5.configure(_.copy(enforceTransferValidationAfter = 4)), balances) { d =>
        d.appendBlock(preparingTxs*)

        val invoke1 = invoke()
        d.appendBlock(invoke1)
        d.blockchain.transactionSucceeded(invoke1.id.value()) shouldBe true

        d.liquidDiff.portfolios(dApp1) shouldBe Portfolio.build(asset, 1)
        d.liquidDiff.portfolios(dApp2) shouldBe Portfolio.build(asset, -1)

        val invoke2 = invoke()
        d.appendBlockE(invoke2) should produce {
          if (customAsset)
            s"DApp $dApp1 invoked DApp $dApp2 with attached token $asset amount = -1"
          else
            s"DApp $dApp1 invoked DApp $dApp2 with attached WAVES amount = -1"
        }
      }
    }
  }

  property("negative sync dApp payments are forbidden before and after RideV6 activation") {
    val (balances, preparingTxs, invoke, dApp1, dApp2, _) =
      scenario(bigComplexityDApp1 = false, bigComplexityDApp2 = false, customAsset = false, -1)
    withDomain(DomainPresets.RideV5.configure(_.copy(enforceTransferValidationAfter = 0)).setFeaturesHeight(RideV6 -> 4), balances) { d =>
      val errMsg = s"DApp $dApp1 invoked DApp $dApp2 with attached WAVES amount = -1"

      d.appendBlock(preparingTxs*)

      val invoke1 = invoke()
      d.appendBlockE(invoke1) should produceRejectOrFailedDiff(errMsg)

      d.appendBlock()
      val invoke2 = invoke()
      d.appendBlockE(invoke2) should produceRejectOrFailedDiff(errMsg)
    }
  }

  property("zero sync dApp payments are allowed before and forbidden after RideV6 activation") {
    val (balances, preparingTxs, invoke, dApp1, dApp2, _) =
      scenario(bigComplexityDApp1 = false, bigComplexityDApp2 = false, customAsset = false, 0)
    withDomain(DomainPresets.RideV5.configure(_.copy(enforceTransferValidationAfter = 0)).setFeaturesHeight(RideV6 -> 4), balances) { d =>
      d.appendBlock(preparingTxs*)

      val invoke1 = invoke()
      d.appendAndAssertSucceed(invoke1)

      val invoke2 = invoke()
      d.appendBlockE(invoke2) should produceRejectOrFailedDiff(s"DApp $dApp1 invoked DApp $dApp2 with attached WAVES amount = 0")
    }
  }

  property("sync dApp self-payment is forbidden") {
    val invoker = TxHelpers.signer(0)
    val dApp    = TxHelpers.signer(1)

    withDomain(RideV5, AddrWithBalance.enoughBalances(invoker, dApp)) { d =>
      val setScript = TxHelpers.setScript(dApp, selfInvokeDAppScript)
      val invoke    = TxHelpers.invoke(dApp.toAddress, func = Some("foo"), invoker = invoker)

      d.appendBlock(setScript)

      d.appendBlockE(invoke) should produce("DApp self-payment is forbidden since V4")
    }
  }

  property("payments in sync call should change sender/recipient waves balance") {
    val invoker       = TxHelpers.signer(1)
    val senderDApp    = TxHelpers.signer(2)
    val recipientDApp = TxHelpers.signer(3)

    withDomain(RideV5, AddrWithBalance.enoughBalances(invoker, senderDApp, recipientDApp)) { d =>
      val paymentAmount      = 100
      val setSenderScript    = TxHelpers.setScript(senderDApp, invokerDAppScript(recipientDApp.toAddress, amount = paymentAmount))
      val setRecipientScript = TxHelpers.setScript(recipientDApp, simpleDAppScript())
      val invoke             = TxHelpers.invoke(senderDApp.toAddress, invoker = invoker)

      d.balance(senderDApp.toAddress) shouldBe ENOUGH_AMT
      d.balance(recipientDApp.toAddress) shouldBe ENOUGH_AMT

      d.appendBlock(setSenderScript, setRecipientScript)
      d.appendBlock(invoke)

      d.balance(senderDApp.toAddress) shouldBe ENOUGH_AMT - setSenderScript.fee.value - paymentAmount
      d.balance(recipientDApp.toAddress) shouldBe ENOUGH_AMT - setRecipientScript.fee.value + paymentAmount
    }
  }

  property("should fail invoke when doesn't have enough asset amount for payment before sync call") {
    val invoker     = TxHelpers.signer(1)
    val masterDApp  = TxHelpers.signer(2)
    val serviceDApp = TxHelpers.signer(3)

    withDomain(RideV5, AddrWithBalance.enoughBalances(invoker, serviceDApp) :+ AddrWithBalance(masterDApp.toAddress, 1.waves)) { d =>
      val paymentAmount = 10.waves

      val setMasterScript  = TxHelpers.setScript(masterDApp, invokerDAppScript(serviceDApp.toAddress, amount = paymentAmount))
      val setServiceScript = TxHelpers.setScript(serviceDApp, simpleDAppScript())
      val invoke           = TxHelpers.invoke(masterDApp.toAddress, invoker = invoker)

      d.appendBlock(setMasterScript, setServiceScript)

      d.balance(masterDApp.toAddress) < paymentAmount shouldBe true

      d.appendBlockE(invoke) should produce(s"${masterDApp.toAddress} -> negative waves balance")
    }
  }

  property("negative balance rejects or fails") {
    for {
      bigComplexityDApp1 <- Seq(false, true)
      bigComplexityDApp2 <- Seq(false, true)
    } {
      val invoker = TxHelpers.signer(0)
      val dApp1   = TxHelpers.signer(1)
      val dApp2   = TxHelpers.signer(2)

      val balances = AddrWithBalance.enoughBalances(invoker, dApp2) :+ AddrWithBalance(dApp1.toAddress, 0.01.waves)

      val setScript1 = TxHelpers.setScript(dApp1, invokerDAppScript(dApp2.toAddress, bigComplexityDApp1, amount = 100))
      val setScript2 = TxHelpers.setScript(dApp2, transferDAppScript(100, bigComplexityDApp2))

      val preparingTxs = Seq(setScript1, setScript2)

      val invoke = TxHelpers.invoke(dApp1.toAddress, func = None, invoker = invoker)

      withDomain(DomainPresets.RideV6, balances) { d =>
        d.appendBlock(preparingTxs*)

        if (!bigComplexityDApp1 && !bigComplexityDApp2) {
          d.appendAndCatchError(invoke).toString should include("negative waves balance")
        } else {
          d.appendAndAssertFailed(invoke)
        }
      }
    }
  }

  property("can't use funds received by leasing for sync call payment") {
    val invoker     = TxHelpers.signer(1)
    val masterDApp  = TxHelpers.signer(2)
    val serviceDApp = TxHelpers.signer(3)

    withDomain(RideV5, AddrWithBalance.enoughBalances(invoker, serviceDApp) :+ AddrWithBalance(masterDApp.toAddress, 1.waves)) { d =>
      val paymentAmount = 10.waves

      val setMasterScript  = TxHelpers.setScript(masterDApp, invokerDAppScript(serviceDApp.toAddress, amount = paymentAmount))
      val setServiceScript = TxHelpers.setScript(serviceDApp, simpleDAppScript())
      val lease            = TxHelpers.lease(invoker, masterDApp.toAddress, paymentAmount)
      val invoke           = TxHelpers.invoke(masterDApp.toAddress, invoker = invoker)

      d.appendBlock(setMasterScript, setServiceScript, lease)

      val balanceDetails = d.accountsApi.balanceDetails(masterDApp.toAddress).explicitGet()

      balanceDetails.leaseIn shouldBe paymentAmount
      balanceDetails.available < paymentAmount shouldBe true

      d.appendBlockE(invoke) should produce(s"${masterDApp.toAddress} -> negative waves balance")
    }
  }

  property("can use leased funds for sync call payment before allow-leased-balance-transfer-until") {
    val invoker     = TxHelpers.signer(1)
    val masterDApp  = TxHelpers.signer(2)
    val serviceDApp = TxHelpers.signer(3)

    withDomain(RideV5.configure(_.copy(blockVersion3AfterHeight = 100)), AddrWithBalance.enoughBalances(invoker, serviceDApp, masterDApp)) { d =>
      val paymentAmount = 10.waves
      val leaseAmount   = ENOUGH_AMT - paymentAmount

      val setMasterScript  = TxHelpers.setScript(masterDApp, invokerDAppScript(serviceDApp.toAddress, amount = paymentAmount))
      val setServiceScript = TxHelpers.setScript(serviceDApp, simpleDAppScript())
      val lease            = TxHelpers.lease(masterDApp, invoker.toAddress, leaseAmount)
      val invoke           = TxHelpers.invoke(masterDApp.toAddress, invoker = invoker)

      d.appendBlock(setMasterScript, setServiceScript, lease)

      val balanceDetails = d.accountsApi.balanceDetails(masterDApp.toAddress).explicitGet()

      balanceDetails.leaseOut shouldBe leaseAmount
      balanceDetails.available < paymentAmount shouldBe true

      d.appendAndAssertSucceed(invoke)
    }
  }

  property("can't use leased funds for sync call payment after allow-leased-balance-transfer-until") {
    val invoker     = TxHelpers.signer(1)
    val masterDApp  = TxHelpers.signer(2)
    val serviceDApp = TxHelpers.signer(3)

    withDomain(RideV5.configure(_.copy(blockVersion3AfterHeight = 2)), AddrWithBalance.enoughBalances(invoker, serviceDApp, masterDApp)) { d =>
      val paymentAmount = 10.waves
      val leaseAmount   = ENOUGH_AMT - paymentAmount

      val setMasterScript  = TxHelpers.setScript(masterDApp, invokerDAppScript(serviceDApp.toAddress, amount = paymentAmount))
      val setServiceScript = TxHelpers.setScript(serviceDApp, simpleDAppScript())
      val lease            = TxHelpers.lease(masterDApp, invoker.toAddress, leaseAmount)
      val invoke           = TxHelpers.invoke(masterDApp.toAddress, invoker = invoker)

      d.appendBlock(setMasterScript, setServiceScript, lease)

      val balanceDetails = d.accountsApi.balanceDetails(masterDApp.toAddress).explicitGet()

      balanceDetails.leaseOut shouldBe leaseAmount
      balanceDetails.available < paymentAmount shouldBe true

      d.appendBlockE(invoke) should produce(s"${masterDApp.toAddress} -> negative effective balance")
    }
  }

  property("should not allow payments overflow") {
    val invoker     = TxHelpers.signer(1)
    val masterDApp  = TxHelpers.signer(2)
    val serviceDApp = TxHelpers.signer(3)

    withDomain(RideV5, AddrWithBalance.enoughBalances(invoker, masterDApp) :+ AddrWithBalance(serviceDApp.toAddress, Long.MaxValue)) { d =>
      val setServiceScript = TxHelpers.setScript(serviceDApp, simpleDAppScript())
      val paymentAmount    = setServiceScript.fee.value + 1
      val setMasterScript  = TxHelpers.setScript(masterDApp, invokerDAppScript(serviceDApp.toAddress, amount = paymentAmount))
      val invoke           = TxHelpers.invoke(masterDApp.toAddress, invoker = invoker)

      d.appendBlock(setMasterScript, setServiceScript)

      Long.MaxValue - d.balance(serviceDApp.toAddress) < paymentAmount

      d.appendBlockE(invoke) should produce(s"${serviceDApp.toAddress} -> Waves balance sum overflow")
    }
  }

  property("should not allow payments with not existing asset") {
    val invoker     = TxHelpers.signer(1)
    val masterDApp  = TxHelpers.signer(2)
    val serviceDApp = TxHelpers.signer(3)

    withDomain(RideV5, AddrWithBalance.enoughBalances(invoker, masterDApp, serviceDApp)) { d =>
      val notExistingAsset = IssuedAsset(ByteStr.fill(32)(1))
      val setMasterScript  = TxHelpers.setScript(masterDApp, invokerDAppScript(serviceDApp.toAddress, asset = notExistingAsset))
      val setServiceScript = TxHelpers.setScript(serviceDApp, simpleDAppScript())
      val invoke           = TxHelpers.invoke(masterDApp.toAddress, invoker = invoker)

      d.appendBlock(setMasterScript, setServiceScript)

      d.assetsApi.description(notExistingAsset) shouldBe None

      d.appendBlockE(invoke) should produce(s"Transfer error: asset '${notExistingAsset.id}' is not found on the blockchain")
    }
  }

  private def sigVerify(c: Boolean) =
    s""" strict c = ${if (c) (1 to 5).map(_ => "sigVerify(base58'', base58'', base58'')").mkString(" || ") else "true"} """

  private def invokerDAppScript(dApp: Address, bigComplexity: Boolean = false, asset: Asset = Waves, amount: Long = 1.waves): Script =
    TestCompiler(V5).compileContract(
      s"""
         | @Callable(i)
         | func default() = {
         |   ${sigVerify(bigComplexity)}
         |   let asset = ${asset.fold("unit")(a => s"base58'$a'")}
         |   strict r = Address(base58'$dApp').invoke("default", [], [AttachedPayment(asset, $amount)])
         |   []
         | }
       """.stripMargin
    )

  private def simpleDAppScript(bigComplexity: Boolean = false): Script =
    TestCompiler(V5).compileContract(
      s"""
         | @Callable(i)
         | func default() = {
         |   ${sigVerify(bigComplexity)}
         |   []
         | }
       """.stripMargin
    )

  private def transferDAppScript(amount: Long, bigComplexity: Boolean): Script =
    TestCompiler(V5).compileContract(
      s"""
         | @Callable(i)
         | func default() = {
         |   ${sigVerify(bigComplexity)}
         |   [
         |     ScriptTransfer(i.caller, $amount, unit)
         |   ]
         | }
       """.stripMargin
    )

  private def selfInvokeDAppScript: Script =
    TestCompiler(V5).compileContract(
      s"""
         |{-# STDLIB_VERSION 5 #-}
         |{-# CONTENT_TYPE DAPP #-}
         |{-# SCRIPT_TYPE ACCOUNT #-}
         |
         |@Callable (i)
         |func foo() = {
         |  strict inv = invoke(this, "bar", [], [AttachedPayment(unit, 100)])
         |  ([], nil)
         |}
         |
         |@Callable (i)
         |func bar() = {
         |  ([], nil)
         |}
         |
         """.stripMargin
    )

  private def scenario(bigComplexityDApp1: Boolean, bigComplexityDApp2: Boolean, customAsset: Boolean, amount: Long) = {
    val invoker = TxHelpers.signer(0)
    val dApp1   = TxHelpers.signer(1)
    val dApp2   = TxHelpers.signer(2)

    val balances = AddrWithBalance.enoughBalances(invoker, dApp1, dApp2)

    val issue = TxHelpers.issue(dApp2, 100)
    val asset = if (customAsset) IssuedAsset(issue.id()) else Waves
    val setScript = Seq(
      TxHelpers.setScript(dApp1, invokerDAppScript(dApp2.toAddress, bigComplexityDApp1, asset, amount)),
      TxHelpers.setScript(dApp2, simpleDAppScript(bigComplexityDApp2))
    )

    val invoke = () => TxHelpers.invoke(dApp1.toAddress, invoker = invoker)

    (balances, issue +: setScript, invoke, dApp1.toAddress, dApp2.toAddress, asset)
  }
}
