package hmda.validation.rules.lar.validity

import hmda.model.fi.lar.LoanApplicationRegister
import hmda.validation.dsl.Result
import hmda.validation.rules.EditCheck

object V520 extends EditCheck[LoanApplicationRegister] {
  override def name: String = "V520"

  override def apply(lar: LoanApplicationRegister): Result = {
    when(lar.lienStatus is equalTo(3)) {
      lar.rateSpread is equalTo("NA")
    }
  }
}