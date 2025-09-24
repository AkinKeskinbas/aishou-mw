package aishou.infra.security

import okio.ByteString.Companion.encodeUtf8

fun sha256(s:String) = s.encodeUtf8().sha256().hex()

fun sortedPairId(a:String, b:String) = listOf(a,b).sorted().joinToString("_")