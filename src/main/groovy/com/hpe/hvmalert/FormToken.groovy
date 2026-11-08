package com.hpe.hvmalert

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Account

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ties a save to the cluster page. Morpheus renders the HVM Alert tab only inside the cluster's detail page, which it
 * shows only to users with access to that cluster (group access included); the tab embeds a token signed for that
 * cluster, and the controller accepts a save only with a valid one. The route permission alone (Clusters: Full) would
 * let a user change the alert mail of a cluster they cannot open. The Plugin API gives the controller no per-user
 * group-access check (DataQuery(user) is "not guaranteed to restrict based on resource permissions").
 * The signing key is per tenant in Cypher (secret/hvm-cluster-alert/form-key), created on the first render.
 */
class FormToken {

    static final String KEY = 'secret/hvm-cluster-alert/form-key'
    static final long TTL_MS = 8L * 3600L * 1000L

    static String issue(String secret, Long clusterId, long nowMs) {
        long expires = nowMs + TTL_MS
        "${expires}.${mac(secret, clusterId, expires)}".toString()
    }

    static boolean valid(String secret, Long clusterId, Object token, long nowMs) {
        if (!secret || clusterId == null || !(token instanceof String)) return false
        String[] parts = ((String) token).split('\\.')
        if (parts.length != 2 || !(parts[0] ==~ /\d{1,15}/)) return false
        long expires = parts[0].toLong()
        if (expires < nowMs || expires > nowMs + TTL_MS) return false
        MessageDigest.isEqual(mac(secret, clusterId, expires).getBytes(StandardCharsets.US_ASCII), parts[1].getBytes(StandardCharsets.US_ASCII))
    }

    static String mac(String secret, Long clusterId, long expires) {
        Mac m = Mac.getInstance('HmacSHA256')
        m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), 'HmacSHA256'))
        m.doFinal("hvm-cluster-alert:${clusterId}:${expires}".toString().getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    /** The tenant's signing key; with create, a new random one is stored when there is none. */
    static String secret(MorpheusContext morpheus, Account owner, boolean create) {
        String s = TabSettings.read(morpheus, owner, KEY)
        if (!s && create) {
            byte[] b = new byte[32]
            new SecureRandom().nextBytes(b)
            s = b.encodeHex().toString()
            TabSettings.write(morpheus, owner, KEY, s)
        }
        s
    }
}
