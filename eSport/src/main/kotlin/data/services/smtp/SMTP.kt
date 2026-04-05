package com.sportenth.data.services.smtp

import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.*

fun sendVerificationCode(email: String, code: String) {
    val username = "rodionov.mikhail.a@yandex.ru"
    val password = "zbketatgxwmryfvh"

    val props = Properties().apply {
        put("mail.smtp.auth", "true")
        put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.host", "smtp.yandex.ru")
        put("mail.smtp.port", "587")
    }

    val session = Session.getInstance(props, object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication {
            return PasswordAuthentication(username, password)
        }
    })

    val message = MimeMessage(session).apply {
        setFrom(InternetAddress(username))
        setRecipients(Message.RecipientType.TO, InternetAddress.parse(email))
        subject = "Your verification code"
        setText("Your verification code is: $code")
    }

    Transport.send(message)
    println("📧 Sent code $code to $email")
}
