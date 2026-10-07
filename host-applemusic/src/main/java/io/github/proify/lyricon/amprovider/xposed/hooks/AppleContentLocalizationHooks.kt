/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed.hooks

import android.net.Uri
import android.os.SystemClock
import com.juren233.hyperlyricsenhanced.BuildConfig
import dev.amenhancer.module.config.CatalogLanguagePolicy
import io.github.libxposed.api.XposedInterface.Chain
import io.github.proify.lyricon.amprovider.xposed.AppleContentHttpTimingTracker
import io.github.proify.lyricon.amprovider.xposed.AppleInternalCatalogResolver
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookPoint
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookProfiles
import io.github.proify.lyricon.amprovider.xposed.AppleMusicHookTarget
import io.github.proify.lyricon.amprovider.xposed.AppleMusicProviderRuntime
import io.github.proify.lyricon.amprovider.xposed.AppleMusicRuntimeMember
import io.github.proify.lyricon.amprovider.xposed.AppleReflection
import io.github.proify.lyricon.amprovider.xposed.ProviderLogger
import io.github.proify.lyricon.amprovider.xposed.accountStorefrontForPlaybackRequest
import io.github.proify.lyricon.amprovider.xposed.configuredLanguageOrNull
import io.github.proify.lyricon.amprovider.xposed.configuredStorefrontOrNull
import io.github.proify.lyricon.amprovider.xposed.isAppleLyricsRequestPath
import io.github.proify.lyricon.amprovider.xposed.isGlobalRegionRewriteEnabled
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Bounded trace budgets for the visible-channel region-seam decision lines. */
private const val CATALOG_SEAM_TRACE_LIMIT = 300
private const val CATALOG_EXECUTOR_TRACE_LIMIT = 200

/**
 * What a content-HTTP seam does with one request.
 *
 * The decision is pure so the region rewrite's module-owned cases stay verifiable on the JVM
 * without a device: an executor-marked request is already localized for its own target and only
 * has its marker stripped; a request that still carries the resolver token but whose localization
 * can no longer be resolved is a module request seen by a later seam and must fail open rather
 * than be re-localized to the configured region; everything else follows the user's region.
 */
internal enum class CatalogSeamAction {
    /** The catalog executor already wrote this request's storefront; only strip the marker. */
    SKIP_MARKED,

    /** Resolver-owned but its localization is gone: never apply the configured region. */
    FAIL_OPEN_UNRESOLVED_TOKEN,

    /** Ordinary traffic while the region feature is off: leave it exactly as Apple built it. */
    PASS,

    /** A resolver-owned request with its own target: rewrite to the token's storefront. */
    REWRITE_MODULE,

    /** Ordinary traffic while the region feature is on: rewrite to the configured storefront. */
    REWRITE_REGION,
}

internal fun catalogSeamAction(
    carriesModuleMarker: Boolean,
    requestToken: String?,
    localizationResolved: Boolean,
    globalRegionRewriteEnabled: Boolean,
): CatalogSeamAction = when {
    carriesModuleMarker -> CatalogSeamAction.SKIP_MARKED
    requestToken != null && !localizationResolved ->
        CatalogSeamAction.FAIL_OPEN_UNRESOLVED_TOKEN
    !localizationResolved && !globalRegionRewriteEnabled -> CatalogSeamAction.PASS
    localizationResolved -> CatalogSeamAction.REWRITE_MODULE
    else -> CatalogSeamAction.REWRITE_REGION
}

internal class AppleContentLocalizationHooks(
    private val runtime: AppleMusicProviderRuntime,
    private val catalogResolver: () -> AppleInternalCatalogResolver,
) {
    @Volatile
    private var lastLoggedContentLanguage: String? = null
    private val contentRequestTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val contentRequestDecisionTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val contentRequestHeaderTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val mediaApiLocalizationTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val mediaApiGlobalTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val catalogSeamTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val catalogExecutorTraceKeys = ConcurrentHashMap.newKeySet<String>()
    private val contentHttpTimingTracker by lazy {
        AppleContentHttpTimingTracker(clock = SystemClock::elapsedRealtime)
    }
    private lateinit var contentHttpTarget: AppleMusicHookTarget

    fun installMediaApiLocalization() {
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(
                AppleMusicHookPoint.MEDIA_API_LOCALIZATION
            )
            val method = resolved.method
            runtime.hookRegistrar.installHook(method, after = { _, result ->
                @Suppress("UNCHECKED_CAST")
                val params = result as? MutableMap<Any?, Any?> ?: return@installHook
                val resolver = catalogResolver()
                val requestToken = params[
                    AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
                ]?.toString()
                val requestLocalization = resolver.catalogRequestLocalization(requestToken)
                    ?: resolver.activeCatalogRequestLocalization()
                // A resolver-owned token always wins.  Ordinary MediaApi requests are only
                // touched when the user turned on a region replacement, so the historical
                // "account region stays untouched" behaviour survives a disabled feature.
                // This mirrors HLE: the request language is always written, including for the
                // untargeted identity probe, whose response language is what decides whether
                // its candidate alias is the native original name or the romanized one.
                val configuredLanguage = resolver.configuredLanguageOrNull()
                val language = requestLocalization?.language
                    ?: configuredLanguage
                    ?: return@installHook
                // Every native MediaApi request passes through here and this map belongs to the
                // host, so only write when the value actually changes: re-writing the same entry
                // cannot help and could invalidate host-side state keyed on the parameters.
                if (params["l"] == language) return@installHook
                params["l"] = language
                if (lastLoggedContentLanguage != language) {
                    lastLoggedContentLanguage = language
                    ProviderLogger.info(
                        "Apple Music 内容本地化参数已覆盖: language=$language"
                    )
                }
                if (
                    BuildConfig.DEBUG &&
                    requestToken != null &&
                    mediaApiLocalizationTraceKeys.add(requestToken)
                ) {
                    ProviderLogger.diagnostic(
                        "AppleCatalogLocalizationParams: token=$requestToken, " +
                            "resolved=${requestLocalization != null}, " +
                            "storefront=${requestLocalization?.storefront ?: "fallback"}, " +
                            "language=${requestLocalization?.language ?: language}"
                    )
                }
                if (
                    BuildConfig.DEBUG &&
                    requestToken == null &&
                    configuredLanguage != null &&
                    mediaApiGlobalTraceKeys.add(configuredLanguage)
                ) {
                    ProviderLogger.diagnostic(
                        "AppleCatalogLocalizationParams: native request localized to " +
                            "language=$configuredLanguage"
                    )
                }
            })
            ProviderLogger.info(
                "Apple Music 内容本地化参数 Hook 已安装: " +
                    "${resolved.target.className}#${method.name}, " +
                    "fallback=${resolved.compatibilityFallback}"
            )
        }.onFailure {
            ProviderLogger.error("Apple Music 内容本地化参数 Hook 安装失败", it)
        }
    }

    /**
     * 目录直连执行器（6.5.3 v8.D/A5.l/Ic.n 请求方法）参数级本地化改写。
     *
     * storefront 恒为参数 index 3（"/v1/catalog/{arg3}/"、"/v1/editorial/{arg3}/" 路径段，
     * 各方法字节码逐一验证）；查询表索引随方法形状不同（d/e 在 5，b/c 在 4——v8.D.b 的
     * arg4 是 query、arg5 是 headers），由安装器从 Method 签名取「第一个 Map 参数」得出。
     *
     * 识别模块请求的唯一切入点是查询表里的 [AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM]；
     * 无论能否解析出目标 storefront，该 token 都必须从出网请求中移除。原生请求无 token：
     * 配置了内容地区时按配置值改写 storefront，未配置则不动。
     */
    fun installCatalogRequestLocalization() {
        // Only a version with its own verified targets may install this seam.  Without them the
        // resolver falls through to the DexKit structural layer, where the only possible outcomes
        // are "hook an unverified method" or "fail" — and a failure is not cached, so the scan
        // repeats on every cold start (on 6.5.2 it degenerates into a whole-DEX query).  The
        // project's rule is to stay uninstalled and report it, which is also what README promises
        // for 6.5.1/6.5.2.
        val exactTargets = AppleMusicHookProfiles.exactTargets(
            runtime.hookResolver.version,
            AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR,
        )
        if (exactTargets.isEmpty()) {
            ProviderLogger.info(
                "Apple Music 目录直连请求 storefront Hook 未安装: 本版本无已验证执行器目标"
            )
            return
        }
        val resolvedClasses = runCatching {
            runtime.hookResolver.resolveClasses(
                AppleMusicHookPoint.MEDIA_API_CATALOG_REQUEST_EXECUTOR
            )
        }.getOrNull()
        if (resolvedClasses.isNullOrEmpty()) {
            ProviderLogger.info(
                "Apple Music 目录直连请求 storefront Hook 未安装: 执行器目标类解析失败"
            )
            return
        }
        // resolveClasses 按类去重（v8.D 的 d 和 b 共用一个类条目），必须按档案目标
        // 逐个匹配方法安装，漏一个目标就是一条请求通道（如批量加载 v8.D#b）。
        val classesByTarget = resolvedClasses.associateBy { it.target.className }
        var installed = 0
        exactTargets.forEach { target ->
            val resolved = classesByTarget[target.className]
            if (resolved == null) {
                ProviderLogger.info(
                    "Apple Music 目录直连请求 storefront Hook 目标类缺失: ${target.className}"
                )
                return@forEach
            }
            runCatching {
                val method = resolved.clazz.declaredMethods
                    .filter { candidate ->
                        candidate.name == target.methodName &&
                            candidate.parameterCount == target.parameterCount
                    }
                    .filter { candidate ->
                        val expected = target.parameterTypeNames
                        expected == null || expected.indices.all { index ->
                            expected[index] == null ||
                                expected[index] == candidate.parameterTypes[index].name
                        }
                    }
                    .single()
                method.isAccessible = true
                val queryArgIndex = AppleCatalogExecutorArgs.queryArgIndex(method)
                runtime.hookRegistrar.installArgumentRewriteHook(method) { chain ->
                    val resolver = catalogResolver()
                    val result = AppleCatalogExecutorArgs.rewrite(
                        args = chain.args,
                        queryArgIndex = queryArgIndex,
                        configuredStorefront = resolver.configuredStorefrontOrNull(),
                    ) { token ->
                        resolver.catalogRequestLocalization(token)
                    }
                    logCatalogExecutorRewrite(
                        executor = "${resolved.target.className}#${target.methodName}",
                        queryArgIndex = queryArgIndex,
                        originalArgs = chain.args,
                        result = result,
                    )
                    result?.args
                }
                installed += 1
            }.onFailure {
                ProviderLogger.error(
                    "Apple Music 目录直连请求 storefront Hook 安装失败: " +
                        "${target.className}#${target.methodName}",
                    it,
                )
            }
        }
        ProviderLogger.info(
            "Apple Music 目录直连请求 storefront Hook 已安装: executors=" +
                exactTargets.joinToString("/") { "${it.className}#${it.methodName}" } +
                ", installed=$installed"
        )
    }

    fun installContentHttpLocalization() {
        installContentHttpHook(
            hookPoint = AppleMusicHookPoint.CONTENT_HTTP_LOCALIZATION,
            label = "Apple 内容 HTTP 本地化",
            source = "content-http",
        )
    }

    /**
     * amp-api 媒体客户端的网络拦截器（6.5.3 = w8.d#a）：所有内容请求的最终形态都在此执行，
     * 覆盖不经 repository executor 的浏览/编辑页请求（新发现、广播等独立体系）。
     */
    fun installAmpApiHttpLocalization() {
        installContentHttpHook(
            hookPoint = AppleMusicHookPoint.MEDIA_API_AMP_HTTP_INTERCEPTOR,
            label = "Apple amp-api 内容请求网络拦截",
            source = "amp-api",
            // Unlike the content HTTP seam below, this one has no verified owner on 6.5.0-6.5.2
            // and no compatibility candidate that can match, so without this gate its resolution
            // degenerates into a whole-DEX DexKit query for every one-argument method named "a"
            // and then fails — uncached, on every cold start.
            requireExactTargets = true,
        )
    }

    /**
     * Both HTTP seams share one rewrite body.  Their targets carry the same runtime member
     * names (only the owning package moves between versions), so the most recently resolved
     * target describes either seam equally well.
     */
    private fun installContentHttpHook(
        hookPoint: AppleMusicHookPoint,
        label: String,
        source: String,
        requireExactTargets: Boolean = false,
    ) {
        // Deliberately not applied to CONTENT_HTTP_LOCALIZATION: it has no exact target on
        // 6.5.1/6.5.2 either, but those builds reach their verified owner through the
        // compatibility candidate chain, so gating it would disable a working hook.
        if (requireExactTargets &&
            AppleMusicHookProfiles.exactTargets(runtime.hookResolver.version, hookPoint).isEmpty()
        ) {
            ProviderLogger.info("$label Hook 未安装: 本版本无已验证目标")
            return
        }
        runCatching {
            val resolved = runtime.hookResolver.resolveMethod(hookPoint)
            contentHttpTarget = resolved.target
            runtime.hookRegistrar.installHook(
                resolved.method,
                before = { chain -> contentHttpLocalizationBefore(chain, source) },
                after = ::contentHttpLocalizationAfter,
            )
            ProviderLogger.info(
                "$label Hook 已安装: target=${resolved.target.className}#" +
                    "${resolved.target.methodName}"
            )
        }.onFailure {
            ProviderLogger.error("$label Hook 安装失败", it)
        }
    }

    private fun contentHttpLocalizationBefore(chain: Chain, source: String) {
        val httpChain = chain.args.firstOrNull() ?: return
        val request = AppleReflection.field(
            httpChain,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD),
        ) ?: return
        val requestUrl = AppleReflection.field(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD),
        )?.toString().orEmpty()
        val requestUri = Uri.parse(requestUrl)
        startContentHttpTiming(httpChain, requestUri)

        val resolver = catalogResolver()
        val pathSegments = requestUri.pathSegments
        val carriesModuleMarker = requestUri.getQueryParameter(
            AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_PARAM
        ) != null
        val requestToken = requestUri.getQueryParameter(
            AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
        )
        val requestLocalization = resolver.catalogRequestLocalization(requestToken)
            ?: resolver.activeCatalogRequestLocalization()
        // Read the storefront the outgoing request actually carries.  These headers are the
        // second way (next to the URL path segment) Apple can pick a catalog region, so the
        // decision line below records them before and after this seam.
        val sourceAcceptLanguage = requestHeader(request, "Accept-Language")
        val sourceStorefrontHeader = requestHeader(request, "X-Apple-Store-Front")
        val sourceRequestStorefrontHeader =
            requestHeader(request, "X-Apple-Request-Store-Front")

        // Entitlement-bound paths are checked BEFORE the module marker: the catalog executor
        // may already have redirected such a request to the configured region, and leaving it
        // there is exactly what makes account-available radio/lyrics unplayable.  These paths
        // are never used by the module's own catalog lookups, so pulling them home is always
        // correct.
        if (AppleInternalCatalogResolver.isAccountScopedPlaybackPath(pathSegments) ||
            isAppleLyricsRequestPath(pathSegments)
        ) {
            val accountStorefront = resolver.accountStorefrontForPlaybackRequest()
            logCatalogSeamDecision(
                source = source,
                uri = requestUri,
                action = "account-storefront",
                requestToken = requestToken,
                marker = carriesModuleMarker,
                resolved = requestLocalization != null,
                targetStorefront = accountStorefront,
                targetLanguage = null,
                acceptLanguage = sourceAcceptLanguage,
                storefrontHeader = sourceStorefrontHeader,
                requestStorefrontHeader = sourceRequestStorefrontHeader,
            )
            val rewritten = rewriteAccountScopedRequest(
                request = request,
                uri = requestUri,
                accountStorefront = accountStorefront,
            ) ?: return
            AppleReflection.setField(
                httpChain,
                member(AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD),
                rewritten,
            )
            if (BuildConfig.DEBUG) {
                ProviderLogger.info(
                    "Apple 账号域请求回退账号 storefront: " +
                        "${AppleInternalCatalogResolver.storefrontFromContentPath(pathSegments)
                            ?: "none"}->${accountStorefront ?: "unchanged"}, " +
                        "moduleParams=${carriesModuleMarker || requestToken != null}"
                )
            }
            return
        }

        // The seam's action for this request: an executor-marked request was already localized
        // for its own target and is only stripped, a module request whose localization is gone
        // fails open, and everything else follows the user's region.
        val seamAction = catalogSeamAction(
            carriesModuleMarker = carriesModuleMarker,
            requestToken = requestToken,
            localizationResolved = requestLocalization != null,
            globalRegionRewriteEnabled = resolver.isGlobalRegionRewriteEnabled(),
        )
        // The catalog executor already localized this request by rewriting its storefront
        // argument.  Rewriting again here could undo a deliberate original-region target, so
        // only strip the module's own parameters so they never reach Apple.
        if (seamAction == CatalogSeamAction.SKIP_MARKED) {
            logCatalogSeamDecision(
                source = source,
                uri = requestUri,
                action = "skip-marked",
                requestToken = requestToken,
                marker = true,
                resolved = requestLocalization != null,
                targetStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(
                    pathSegments
                ),
                targetLanguage = requestUri.getQueryParameter("l"),
                acceptLanguage = sourceAcceptLanguage,
                storefrontHeader = sourceStorefrontHeader,
                requestStorefrontHeader = sourceRequestStorefrontHeader,
            )
            stripModuleParameters(request, requestUri)?.let { stripped ->
                AppleReflection.setField(
                    httpChain,
                    member(AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD),
                    stripped,
                )
            }
            return
        }

        // Resolver-owned but its localization is no longer attached: a later seam is seeing a
        // request whose marker an earlier seam already stripped.  Applying the configured
        // region here is exactly what turns a targeted original-region lookup into a configured
        // region one, so fail open and leave Apple's own storefront untouched.  Only the
        // module's own parameters are removed.
        if (seamAction == CatalogSeamAction.FAIL_OPEN_UNRESOLVED_TOKEN) {
            logCatalogSeamDecision(
                source = source,
                uri = requestUri,
                action = "fail-open-unresolved-token",
                requestToken = requestToken,
                marker = false,
                resolved = false,
                targetStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(
                    pathSegments
                ),
                targetLanguage = requestUri.getQueryParameter("l"),
                acceptLanguage = sourceAcceptLanguage,
                storefrontHeader = sourceStorefrontHeader,
                requestStorefrontHeader = sourceRequestStorefrontHeader,
            )
            stripModuleParameters(request, requestUri)?.let { stripped ->
                AppleReflection.setField(
                    httpChain,
                    member(AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD),
                    stripped,
                )
            }
            return
        }

        // Preserve the historical behaviour for ordinary traffic while the feature is off.
        if (seamAction == CatalogSeamAction.PASS) return

        val storefront = requestLocalization?.storefront
            ?: resolver.configuredStorefrontOrNull()
            ?: return
        val language = requestLocalization?.language
            ?: resolver.configuredLanguageOrNull()
            ?: return
        logContentRequestLocalizationDecision(
            uri = requestUri,
            requestToken = requestToken,
            requestLocalization = requestLocalization,
            targetStorefront = storefront,
            targetLanguage = language,
        )
        logCatalogSeamDecision(
            source = source,
            uri = requestUri,
            action = if (requestLocalization != null) "rewrite-module" else "rewrite-region",
            requestToken = requestToken,
            marker = false,
            resolved = requestLocalization != null,
            targetStorefront = storefront,
            targetLanguage = language,
            acceptLanguage = sourceAcceptLanguage,
            storefrontHeader = sourceStorefrontHeader,
            requestStorefrontHeader = sourceRequestStorefrontHeader,
            targetAcceptLanguage = CatalogLanguagePolicy.normalize(language),
            targetStorefrontHeader = AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                storefront = storefront,
                currentValue = sourceStorefrontHeader,
            ),
            targetRequestStorefrontHeader =
                AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                    storefront = storefront,
                    currentValue = sourceRequestStorefrontHeader,
                ),
        )
        val rewritten = rewriteContentRequest(
            request = request,
            uri = requestUri,
            pathSegments = pathSegments,
            storefront = storefront,
            language = language,
            requestToken = requestToken,
        ) ?: return
        AppleReflection.setField(
            httpChain,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_CHAIN_REQUEST_FIELD),
            rewritten,
        )
    }

    /**
     * One bounded, visible-channel decision line per module-owned content request (token or
     * executor marker): which seam saw it, the URL storefront it currently carries, whether the
     * resolver token was present and resolvable, what this seam did, the storefront/language it
     * targeted, and the two catalog storefront headers before and after.
     *
     * This is the evidence that separates "the request was answered by the storefront it asked
     * for" from "a later seam re-localized it to the configured region".  Like the neighbouring
     * catalog traces it uses [ProviderLogger.info], not the `[debug]`-filtered diagnostic
     * channel, and is bounded so a long session cannot flood the log.
     */
    private fun logCatalogSeamDecision(
        source: String,
        uri: Uri,
        action: String,
        requestToken: String?,
        marker: Boolean,
        resolved: Boolean,
        targetStorefront: String?,
        targetLanguage: String?,
        acceptLanguage: String?,
        storefrontHeader: String?,
        requestStorefrontHeader: String?,
        targetAcceptLanguage: String? = acceptLanguage,
        targetStorefrontHeader: String? = storefrontHeader,
        targetRequestStorefrontHeader: String? = requestStorefrontHeader,
    ) {
        if (catalogSeamTraceKeys.size >= CATALOG_SEAM_TRACE_LIMIT) return
        val key = listOf(
            source, action, uri.host, uri.encodedPath, uri.query,
            targetStorefront, targetLanguage, marker, requestToken,
        ).joinToString("|")
        if (!catalogSeamTraceKeys.add(key)) return
        ProviderLogger.info(
            "AppleCatalogSeam: source=$source, action=$action, host=${uri.host.orEmpty()}, " +
                "pathStorefront=" +
                "${AppleInternalCatalogResolver.storefrontFromContentPath(uri.pathSegments)
                    ?: "none"}, " +
                "l=${uri.getQueryParameter("l") ?: "unset"}, " +
                "marker=$marker, token=${requestToken ?: "none"}, resolved=$resolved, " +
                "target=${targetStorefront ?: "none"}/${targetLanguage ?: "none"}, " +
                "acceptLanguage=${acceptLanguage ?: "unset"}->" +
                "${targetAcceptLanguage ?: "unset"}, " +
                "storefrontHeader=${storefrontHeader ?: "unset"}->" +
                "${targetStorefrontHeader ?: "unset"}, " +
                "requestStorefrontHeader=${requestStorefrontHeader ?: "unset"}->" +
                "${targetRequestStorefrontHeader ?: "unset"}"
        )
    }

    /**
     * One bounded, visible-channel line per catalog-executor storefront rewrite.
     *
     * It states the executor target, whether the request carried the resolver's per-request
     * token (and therefore whether `localizationForToken` found the module's own storefront), and
     * the storefront argument before and after.  A module request that reaches this hook
     * *without* a resolvable token is the decisive proof that the executor classified it as
     * native and overwrote the requested original storefront with the configured region.
     */
    private fun logCatalogExecutorRewrite(
        executor: String,
        queryArgIndex: Int,
        originalArgs: List<Any?>,
        result: AppleCatalogExecutorArgs.Result?,
    ) {
        result ?: return
        if (catalogExecutorTraceKeys.size >= CATALOG_EXECUTOR_TRACE_LIMIT) return
        val token = result.token
        val beforeStorefront = originalArgs
            .getOrNull(AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX)
            ?.toString()
        val afterStorefront = result.args
            .getOrNull(AppleCatalogExecutorArgs.STOREFRONT_ARG_INDEX)
            ?.toString()
        val key = "$executor|${token ?: "native"}|${result.storefront}"
        if (!catalogExecutorTraceKeys.add(key)) return
        ProviderLogger.info(
            "AppleCatalogExecutor: executor=$executor, queryArg=$queryArgIndex, " +
                "token=${token ?: "none"}, localized=${token != null}, " +
                "storefront=${result.storefront ?: "unchanged"}, " +
                "arg3=${beforeStorefront ?: "unset"}->${afterStorefront ?: "unset"}"
        )
    }

    private fun contentHttpLocalizationAfter(chain: Chain, result: Any?) {
        finishContentHttpTiming(
            httpChain = chain.args.firstOrNull(),
            response = result,
        )
    }

    private fun startContentHttpTiming(httpChain: Any, uri: Uri) {
        if (!BuildConfig.DEBUG || !uri.host.orEmpty().contains("apple", ignoreCase = true)) return
        val requestToken = uri.getQueryParameter(
            AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
        )
        val source = if (requestToken == null) {
            AppleContentHttpTimingTracker.Source.NATIVE
        } else {
            AppleContentHttpTimingTracker.Source.MODULE
        }
        val start = contentHttpTimingTracker.start(
            requestKey = httpChain,
            descriptor = AppleContentHttpTimingTracker.RequestDescriptor(
                source = source,
                category = contentHttpRequestCategory(uri.pathSegments),
                storefront = AppleInternalCatalogResolver.storefrontFromContentPath(
                    uri.pathSegments
                ),
                pendingModuleRequests = catalogResolver().pendingCatalogRequestCount(),
            ),
        )
        if (start.sourceInFlight == 1) {
            ProviderLogger.diagnostic(
                "AppleContentHttpTiming: event=source_active, " +
                    "source=${source.name.lowercase()}, " +
                    "category=${start.descriptor.category}, " +
                    "storefront=${start.descriptor.storefront ?: "none"}, " +
                    "pendingModule=${start.descriptor.pendingModuleRequests}, " +
                    "totalInFlight=${start.totalInFlight}"
            )
        }
    }

    private fun finishContentHttpTiming(httpChain: Any?, response: Any?) {
        if (!BuildConfig.DEBUG || httpChain == null) return
        val statusCode = response?.let {
            runCatching {
                AppleReflection.intField(
                    it,
                    member(AppleMusicRuntimeMember.CONTENT_HTTP_RESPONSE_STATUS_FIELD),
                )
            }.getOrNull()
        }
        val completion = contentHttpTimingTracker.finish(httpChain, statusCode) ?: return
        if (completion.isSlow) {
            ProviderLogger.diagnostic(
                "AppleContentHttpTiming: event=slow, " +
                    "source=${completion.descriptor.source.name.lowercase()}, " +
                    "category=${completion.descriptor.category}, " +
                    "storefront=${completion.descriptor.storefront ?: "none"}, " +
                    "elapsedMs=${completion.elapsedMs}, code=${completion.statusCode ?: "unknown"}, " +
                    "pendingModuleAtStart=${completion.descriptor.pendingModuleRequests}, " +
                    "sourceInFlight=${completion.sourceInFlight}, " +
                    "totalInFlight=${completion.totalInFlight}"
            )
        }
        completion.summary?.let { summary ->
            ProviderLogger.diagnostic(
                "AppleContentHttpTiming: event=summary, windowMs=${summary.windowMs}, " +
                    "native=${contentHttpTimingStats(summary.native)}, " +
                    "module=${contentHttpTimingStats(summary.module)}, " +
                    "totalInFlight=${summary.totalInFlight}"
            )
        }
    }

    private fun contentHttpTimingStats(
        stats: AppleContentHttpTimingTracker.SourceStats,
    ): String = "{completed=${stats.completed}, avgMs=${stats.averageElapsedMs}, " +
        "maxMs=${stats.maxElapsedMs}, slow=${stats.slowRequests}, " +
        "inFlight=${stats.inFlight}, categories=${stats.categories}}"

    private fun contentHttpRequestCategory(pathSegments: List<String>): String {
        if (isAppleLyricsRequestPath(pathSegments)) return "lyrics"
        val knownCategories = listOf(
            "artists",
            "albums",
            "songs",
            "music-videos",
            "playlists",
            "search",
            "charts",
            "views",
            "recommendations",
        )
        return pathSegments.firstOrNull(knownCategories::contains) ?: "other"
    }

    /**
     * Rewrites one request to [storefront]/[language].
     *
     * The caller has already parsed [uri] and split [pathSegments] for the account-scoped checks,
     * so they are passed in rather than re-derived: this runs for every content request once a
     * region is selected.
     */
    private fun rewriteContentRequest(
        request: Any,
        uri: Uri,
        pathSegments: List<String>,
        storefront: String,
        language: String,
        requestToken: String?,
    ): Any? {
        val url = AppleReflection.field(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD),
        )?.toString().orEmpty()
        val host = uri.host.orEmpty()
        if (!host.contains("apple", ignoreCase = true)) return null

        val segments = pathSegments.toMutableList()
        val pathStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(segments)
        val isPersonalizedContent = segments.take(3) == listOf("v1", "me", "recommendations")
        val isLyricsRequest = isAppleLyricsRequestPath(segments)
        if (pathStorefront == null && !isPersonalizedContent) return null
        if (isLyricsRequest) return null
        if (pathStorefront != null) segments[2] = storefront

        val builder = uri.buildUpon()
        builder.encodedPath(
            segments.joinToString(separator = "/", prefix = "/") { Uri.encode(it) }
        )
        builder.clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (name != "l" &&
                name != AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM &&
                name != AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_PARAM
            ) {
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
        }
        builder.appendQueryParameter("l", language)
        val rewrittenUrl = builder.build().toString()
        // HLE sends the language tag itself; it never downgrades it (ja-JP stays ja-JP),
        // because Apple selects the storefront's own localization from this header.
        val headerLanguage = CatalogLanguagePolicy.normalize(language)
        val sourceAcceptLanguage = requestHeader(request, "Accept-Language")
        val sourceStorefrontHeader = requestHeader(request, "X-Apple-Store-Front")
        val sourceRequestStorefrontHeader =
            requestHeader(request, "X-Apple-Request-Store-Front")
        val targetStorefrontHeader =
            AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                storefront = storefront,
                currentValue = sourceStorefrontHeader,
            )
        val targetRequestStorefrontHeader =
            AppleInternalCatalogResolver.localizedStorefrontHeaderValue(
                storefront = storefront,
                currentValue = sourceRequestStorefrontHeader,
            )
        val hasHeaderChanges =
            sourceAcceptLanguage != headerLanguage ||
                targetStorefrontHeader != sourceStorefrontHeader ||
                targetRequestStorefrontHeader != sourceRequestStorefrontHeader
        if (rewrittenUrl == url && !hasHeaderChanges) return null

        if (rewrittenUrl != url) {
            logContentRequestRewrite(
                uri = uri,
                pathStorefront = pathStorefront,
                targetStorefront = storefront,
                targetLanguage = language,
                personalized = isPersonalizedContent,
            )
        }

        val requestBuilder = AppleReflection.call(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_NEW_BUILDER_METHOD),
        ) ?: return null
        if (rewrittenUrl != url) {
            AppleReflection.call(
                requestBuilder,
                member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD),
                rewrittenUrl,
            )
        }
        val headerMethod =
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_HEADER_METHOD)
        AppleReflection.call(requestBuilder, headerMethod, "Accept-Language", headerLanguage)
        targetStorefrontHeader?.let { value ->
            AppleReflection.call(requestBuilder, headerMethod, "X-Apple-Store-Front", value)
        }
        targetRequestStorefrontHeader?.let { value ->
            AppleReflection.call(
                requestBuilder,
                headerMethod,
                "X-Apple-Request-Store-Front",
                value,
            )
        }
        logContentRequestHeaders(
            requestToken = requestToken,
            sourceAcceptLanguage = sourceAcceptLanguage,
            targetAcceptLanguage = headerLanguage,
            sourceStorefrontHeader = sourceStorefrontHeader,
            targetStorefrontHeader = targetStorefrontHeader,
            sourceRequestStorefrontHeader = sourceRequestStorefrontHeader,
            targetRequestStorefrontHeader = targetRequestStorefrontHeader,
        )
        return AppleReflection.call(
            requestBuilder,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_BUILD_METHOD),
        )
    }

    /**
     * Removes the module's own query parameters from an already-localized request so the
     * `hle_catalog_*` markers never leave the device.
     */
    private fun stripModuleParameters(request: Any, uri: Uri): Any? {
        val builder = uri.buildUpon()
        builder.clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (name != AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_PARAM &&
                name != AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
            ) {
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
        }
        val rewrittenUrl = builder.build().toString()
        if (rewrittenUrl == uri.toString()) return null
        val requestBuilder = AppleReflection.call(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_NEW_BUILDER_METHOD),
        ) ?: return null
        AppleReflection.call(
            requestBuilder,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD),
            rewrittenUrl,
        )
        return AppleReflection.call(
            requestBuilder,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_BUILD_METHOD),
        )
    }

    private fun requestHeader(request: Any, name: String): String? = runCatching {
        val headers = AppleReflection.field(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_HEADERS_FIELD),
        ) ?: return@runCatching null
        (AppleReflection.call(
            headers,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_HEADERS_GET_METHOD),
            name,
        ) as? String)?.trim()?.takeIf(String::isNotEmpty)
    }.getOrNull()

    /**
     * Brings an entitlement-bound request back to the account storefront and removes the
     * module's own query parameters.
     *
     * Both parts are needed: the catalog executor may have redirected the storefront *and*
     * stamped its marker on the same request, and neither must survive.  Returns null when the
     * request already has that shape, so an untouched native request is never rebuilt.
     */
    private fun rewriteAccountScopedRequest(
        request: Any,
        uri: Uri,
        accountStorefront: String?,
    ): Any? {
        if (!uri.host.orEmpty().contains("apple", ignoreCase = true)) return null

        val segments = uri.pathSegments.toMutableList()
        val pathStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(segments)
        val storefrontChanged = accountStorefront != null &&
            pathStorefront != null &&
            pathStorefront != accountStorefront
        if (storefrontChanged) segments[2] = accountStorefront

        val moduleParams = uri.queryParameterNames.filter {
            it == AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_PARAM ||
                it == AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM
        }
        if (!storefrontChanged && moduleParams.isEmpty()) return null

        val builder = uri.buildUpon()
        if (storefrontChanged) {
            builder.encodedPath(
                segments.joinToString(separator = "/", prefix = "/") { Uri.encode(it) }
            )
        }
        if (moduleParams.isNotEmpty()) {
            builder.clearQuery()
            uri.queryParameterNames.forEach { name ->
                if (!moduleParams.contains(name)) {
                    uri.getQueryParameters(name).forEach { value ->
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
        }
        val rewrittenUrl = builder.build().toString()
        val originalUrl = AppleReflection.field(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_URL_FIELD),
        )?.toString().orEmpty()
        if (rewrittenUrl == originalUrl) return null

        val requestBuilder = AppleReflection.call(
            request,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_NEW_BUILDER_METHOD),
        ) ?: return null
        AppleReflection.call(
            requestBuilder,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD),
            rewrittenUrl,
        )
        return AppleReflection.call(
            requestBuilder,
            member(AppleMusicRuntimeMember.CONTENT_HTTP_REQUEST_BUILDER_BUILD_METHOD),
        )
    }

    private fun logContentRequestRewrite(
        uri: Uri,
        pathStorefront: String?,
        targetStorefront: String,
        targetLanguage: String,
        personalized: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        val segments = uri.pathSegments
        val category = when {
            personalized -> "recommendations"
            "library" in segments -> "library"
            "recent" in segments || "history" in segments -> "recent"
            "radio" in segments || "stations" in segments -> "radio"
            "playlists" in segments -> "playlists"
            "albums" in segments -> "albums"
            "artists" in segments -> "artists"
            "songs" in segments -> "songs"
            segments.getOrNull(1) == "me" -> "me"
            else -> "other"
        }
        val safeSegments = setOf(
            "v1", "catalog", "me", "recommendations", "library", "recent", "history",
            "radio", "stations", "playlists", "albums", "artists", "songs", "search",
            "charts", "views", "relationships", "personal-recommendation",
        )
        val pathShape = segments.mapIndexed { index, segment ->
            when {
                index == 2 && pathStorefront != null -> "{storefront}"
                segment in safeSegments -> segment
                else -> "{value}"
            }
        }.joinToString(separator = "/", prefix = "/")
        val sourceLanguage = uri.getQueryParameter("l") ?: "unset"
        val traceKey = "$category:$pathShape:$pathStorefront:$targetStorefront:" +
            "$sourceLanguage:$targetLanguage"
        if (!contentRequestTraceKeys.add(traceKey)) return
        ProviderLogger.info(
            "Apple 内容请求路径改写: host=${uri.host.orEmpty()}, category=$category, " +
                "path=$pathShape, storefront=${pathStorefront ?: "none"}->$targetStorefront, " +
                "language=$sourceLanguage->$targetLanguage"
        )
    }

    private fun logContentRequestLocalizationDecision(
        uri: Uri,
        requestToken: String?,
        requestLocalization: AppleInternalCatalogResolver.CatalogRequestLocalization?,
        targetStorefront: String,
        targetLanguage: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val segments = uri.pathSegments
        if (segments.getOrNull(3) != "songs") return
        val pendingCount = catalogResolver().pendingCatalogRequestCount()
        if (requestToken == null && pendingCount == 0) return
        val sourceStorefront = AppleInternalCatalogResolver.storefrontFromContentPath(segments)
        val sourceLanguage = uri.getQueryParameter("l") ?: "unset"
        val requestKey = uri.getQueryParameter("ids")
            ?: uri.getQueryParameter("filter[isrc]")
            ?: segments.getOrNull(4)
            ?: "none"
        val traceKey = "$requestToken:$requestKey:$sourceStorefront:$sourceLanguage:" +
            "$targetStorefront:$targetLanguage:${requestLocalization != null}"
        if (!contentRequestDecisionTraceKeys.add(traceKey)) return
        ProviderLogger.diagnostic(
            "AppleContentHttpLocalization: token=${requestToken ?: "none"}, " +
                "resolved=${requestLocalization != null}, pending=$pendingCount, " +
                "request=$requestKey, storefront=${sourceStorefront ?: "none"}" +
                "->$targetStorefront, language=$sourceLanguage->$targetLanguage"
        )
    }

    private fun logContentRequestHeaders(
        requestToken: String?,
        sourceAcceptLanguage: String?,
        targetAcceptLanguage: String,
        sourceStorefrontHeader: String?,
        targetStorefrontHeader: String?,
        sourceRequestStorefrontHeader: String?,
        targetRequestStorefrontHeader: String?,
    ) {
        if (!BuildConfig.DEBUG || requestToken == null) return
        if (!contentRequestHeaderTraceKeys.add(requestToken)) return
        ProviderLogger.diagnostic(
            "AppleContentHttpHeaders: token=$requestToken, " +
                "acceptLanguage=${sourceAcceptLanguage ?: "unset"}->$targetAcceptLanguage, " +
                "storefrontHeader=${sourceStorefrontHeader ?: "unset"}" +
                "->${targetStorefrontHeader ?: "unset"}, " +
                "requestStorefrontHeader=${sourceRequestStorefrontHeader ?: "unset"}" +
                "->${targetRequestStorefrontHeader ?: "unset"}"
        )
    }

    private fun member(member: AppleMusicRuntimeMember): String =
        contentHttpTarget.runtimeMemberName(member)
}

/**
 * 目录执行器参数改写（不含 Hook 安装，便于 JVM 单测）。
 *
 * storefront 参数位在各方法字节码中逐一验证过；查询表参数位随方法形状不同，由调用方从
 * Method 签名取「第一个 Map 参数」传入。
 */
internal object AppleCatalogExecutorArgs {
    const val STOREFRONT_ARG_INDEX = 3

    internal class Result(
        val token: String?,
        val storefront: String?,
        val args: Array<Any?>,
    )

    /** 查询表参数位：Method 签名里第一个 Map 类型参数（d/e=arg5，b/c=arg4）。 */
    fun queryArgIndex(method: Method): Int =
        method.parameterTypes.indexOfFirst { Map::class.java.isAssignableFrom(it) }

    fun rewrite(
        args: List<Any?>,
        queryArgIndex: Int,
        configuredStorefront: String?,
        localizationForToken: (String) -> AppleInternalCatalogResolver.CatalogRequestLocalization?,
    ): Result? {
        if (args.size <= queryArgIndex || queryArgIndex <= STOREFRONT_ARG_INDEX) return null
        @Suppress("UNCHECKED_CAST")
        val query = args[queryArgIndex] as? MutableMap<Any?, Any?> ?: return null
        val token = query[AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM] as? String
        if (token == null) {
            // Native request: only the user's configured region may redirect it.
            if (configuredStorefront.isNullOrEmpty()) return null
            query[AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_PARAM] =
                AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_VALUE
            val rewritten = args.toTypedArray()
            rewritten[STOREFRONT_ARG_INDEX] = configuredStorefront
            return Result(null, configuredStorefront, rewritten)
        }
        // Resolver-owned request: the token must never reach the network, and the
        // storefront follows the token's own target rather than the global region.
        query.remove(AppleInternalCatalogResolver.CATALOG_REQUEST_TOKEN_PARAM)
        query[AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_PARAM] =
            AppleInternalCatalogResolver.AMP_HTTP_MODULE_MARKER_VALUE
        val localization = localizationForToken(token)
        val target = localization?.storefront
        val rewritten = args.toTypedArray()
        // A null target is the untargeted identity/ISRC/genre probe.  It has no storefront
        // of its own: leave the argument exactly as Apple's own client built it, or the
        // app's resolution of the account-owned catalog ID is replaced by ours and the
        // identity comes back empty.  The module marker above still keeps the request out
        // of the HTTP region rewrite.
        if (target != null) {
            rewritten[STOREFRONT_ARG_INDEX] = target
        }
        return Result(token, target, rewritten)
    }
}
