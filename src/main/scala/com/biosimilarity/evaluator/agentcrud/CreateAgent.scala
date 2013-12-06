package com.biosimilarity.evaluator.agentcrud
import java.net.URI

case class CreateAgentError(reason: String) extends AgentCRUDResponse
case class CreateAgentResponse(agentURI: URI) extends AgentCRUDResponse

// Creates an agent, setting up the password hash.
// Does not do anything else, like create a default alias, add email, etc.
case class CreateAgentRequest(authType : String, authValue : String) extends AgentCRUDRequest {
  import javax.crypto._
  import javax.crypto.spec.SecretKeySpec
  import java.security._

  import java.util.UUID

  import org.json4s._
  import org.json4s.native.JsonMethods._
  import org.json4s.JsonDSL._

  val userPWDBLabel = fromTermString("""system(pwdb(Salt, Hash, "user", K))""").get
  val adminPWDBLabel = fromTermString("""system(pwdb(Salt, Hash, "admin", K))""").get

  def toHex(bytes: Array[Byte]): String = {
    bytes.map("%02X" format _).mkString
  }

  def saltAndHash(pw: String): (String, Array[Byte]) = {
    val md = MessageDigest.getInstance("SHA1")
    val salt = UUID.randomUUID.toString.substring(0,8)
    md.update(salt.getBytes("utf-8"))
    md.update(pw.getBytes("utf-8"))
    (salt, md.digest)
  }

  def handle(k: AgentCRUDResponse => Unit): Unit = {
    import com.biosimilarity.evaluator.distribution._
    try {
      if (authType != "password") {
        k(CreateAgentError("Only password authentication is currently supported."))
      } else {
        // hash = SHA(salt + authValue)
        val (salt, hash) = saltAndHash(authValue)

        // TODO(mike): explicitly manage randomness pool
        val rand = new SecureRandom()

        // Generate random Agent URI
        val bytes = new Array[Byte](16)
        rand.nextBytes(bytes)
        val uri = new URI("agent://" + toHex(bytes))
        val agentIdCnxn = Conversion.selfConnection(uri)

        // Should we use a public key here instead?
        // Generate K for encrypting the lists of aliases, external identities, etc. on the Agent
        // term = pwdb(salt, K0 = hash, "user", AES_K0(K))
        // post term.toString on (Agent, term)
        {
          import DSLCommLink.mTT
          
          // Since we're encrypting exactly 128 bits, ECB is OK
          val aes = Cipher.getInstance("AES/ECB/NoPadding")
          aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hash, "AES"))
          // Generate K
          rand.nextBytes(bytes)
          // AES_K0(K)
          val aesK = toHex(aes.doFinal(bytes))

          val (erql, erspl) = agentMgr().makePolarizedPair()
          agentMgr().post(erql, erspl)(
            userPWDBLabel,
            List(agentIdCnxn),
            // TODO(mike): do proper context-aware interpolation
            "pwdb(" + List(salt, toHex(hash), "user", aesK).map('"'+_+'"').mkString(",") + ")",
            (optRsrc: Option[mTT.Resource]) => {
              k(CreateAgentResponse(uri))
            }
          )
        }
      }
    } catch {
      case e: Exception => {
        k(CreateAgentError(e.toString))
      }
    }
  }
}
