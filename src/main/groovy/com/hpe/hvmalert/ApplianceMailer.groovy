package com.hpe.hvmalert

import groovy.util.logging.Slf4j

/**
 * Sends mail with the appliance's own SMTP settings (Administration > Settings > Email on Morpheus
 * Enterprise and VME), so the plugin carries no SMTP settings of its own.
 *
 * Why it works this way (lab 9.1.0.1, 2026-11-07):
 * - The Plugin API (1.4.x to 1.6.x) has no mail service; GET /api/appliance-settings masks smtpPassword.
 * - The Grails "mailService" bean in the same JVM is not wired to the appliance settings (it tried
 *   localhost:25). The appliance sends through com.morpheus.SmtpEmailService, which reads the Grails
 *   config keys bertramlabs.plugins.smtpEmail.* and builds a mail session per send; its send(...) method
 *   takes internal recipient objects.
 * So this class reads the same config keys at send time and sends with Spring's JavaMailSenderImpl from
 * the appliance class path. Not part of the published Plugin API; a missing setting is reported.
 */
@Slf4j
class ApplianceMailer {

    static final String CFG = 'bertramlabs.plugins.smtpEmail.'

    private Object ctx

    Object applicationContext() {
        if (ctx != null) return ctx
        List<ClassLoader> loaders = [Thread.currentThread().contextClassLoader, ApplianceMailer.classLoader,
                                     ApplianceMailer.classLoader?.parent].findAll { it != null }
        for (ClassLoader cl : loaders) {
            try {
                Class holders = Class.forName('grails.util.Holders', true, cl)
                Object c = holders.getMethod('getApplicationContext').invoke(null)
                if (c != null) {
                    ctx = c
                    return ctx
                }
            } catch (Throwable ignored) {
                // try the next class loader
            }
        }
        return null
    }

    /** Current appliance SMTP settings, read from the Grails config at call time. */
    Map<String, String> smtpSettings() {
        Object c = applicationContext()
        if (c == null) throw new IllegalStateException('Grails application context not reachable from the plugin')
        Object config = c.getBean('grailsApplication').config
        ['server', 'port', 'username', 'password', 'from', 'tls', 'ssl'].collectEntries { String k ->
            Object v = config.getProperty(CFG + k, Object)
            [(k): v == null ? null : v.toString().trim()]
        }
    }

    /** The settings without secrets, for the log. */
    String describe() {
        try {
            Map<String, String> s = smtpSettings()
            "server=${s.server} port=${s.port} from=${s.from} tls=${s.tls} ssl=${s.ssl} user=${s.username ? 'set' : 'none'} password=${s.password ? 'set' : 'none'}"
        } catch (Throwable t) {
            "<not readable: ${t.class.simpleName}: ${t.message}>"
        }
    }

    void send(List<String> to, String subject, String html) {
        if (!to) throw new IllegalStateException('no mail recipients configured')
        Map<String, String> s = smtpSettings()
        if (!s.server) throw new IllegalStateException('the appliance has no SMTP server set (Administration > Settings > Email)')
        ClassLoader cl = applicationContext().getClass().classLoader
        Object sender = Class.forName('org.springframework.mail.javamail.JavaMailSenderImpl', true, cl).getDeclaredConstructor().newInstance()
        sender.host = s.server
        sender.port = (s.port ?: '25') as int
        boolean auth = s.username as boolean
        if (auth) {
            sender.username = s.username
            sender.password = s.password
        }
        Properties p = new Properties()
        p.setProperty('mail.smtp.auth', auth.toString())
        p.setProperty('mail.smtp.starttls.enable', (s.tls == 'true').toString())
        // with TLS on, never fall back to a plain session (AUTH would go out unencrypted)
        p.setProperty('mail.smtp.starttls.required', (s.tls == 'true').toString())
        p.setProperty('mail.smtp.ssl.enable', (s.ssl == 'true').toString())
        p.setProperty('mail.smtp.connectiontimeout', '15000')
        p.setProperty('mail.smtp.timeout', '15000')
        p.setProperty('mail.smtp.writetimeout', '15000')
        // a relay that refuses one recipient must not silence the others; a partial delivery then counts as sent
        p.setProperty('mail.smtp.sendpartial', 'true')
        sender.javaMailProperties = p

        Object message = sender.createMimeMessage()
        Class helperClass = Class.forName('org.springframework.mail.javamail.MimeMessageHelper', true, cl)
        Object helper = helperClass.getConstructor(Class.forName('jakarta.mail.internet.MimeMessage', true, cl), Boolean.TYPE, String)
                .newInstance(message, false, 'UTF-8')
        if (s.from) helper.setFrom(s.from)
        helper.setTo(to as String[])
        helper.setSubject(subject)
        helper.setText(html, true)
        try {
            sender.send(message)
        } catch (Throwable t) {
            String partial = partialDelivery(t)
            if (partial == null) throw t
            log.warn("[hvm-cluster-alert] mail ${partial}: ${subject}")
        }
    }

    /**
     * With mail.smtp.sendpartial the transport still throws SendFailedException after delivering to the valid recipients
     * (Spring wraps it in MailSendException.failedMessages). Returns a description when something was delivered, else
     * null (then it is a real failure and is retried).
     */
    static String partialDelivery(Throwable t) {
        List<Throwable> seen = []
        try {
            def failed = t.hasProperty('failedMessages') ? t.failedMessages : null
            if (failed instanceof Map) seen.addAll(((Map) failed).values().findAll { it instanceof Throwable })
        } catch (Throwable ignored) { }
        Throwable c = t
        while (c != null && seen.size() < 10) { seen << c; c = c.cause }
        Throwable sf = seen.find { it.class.name.endsWith('SendFailedException') && it.hasProperty('validSentAddresses') && it.validSentAddresses }
        if (sf == null) return null
        List refused = ((sf.hasProperty('invalidAddresses') ? sf.invalidAddresses : null) ?: []) as List
        refused += ((sf.hasProperty('validUnsentAddresses') ? sf.validUnsentAddresses : null) ?: []) as List
        "delivered to ${(sf.validSentAddresses as List)*.toString()}; refused: ${refused*.toString()}".toString()
    }
}
