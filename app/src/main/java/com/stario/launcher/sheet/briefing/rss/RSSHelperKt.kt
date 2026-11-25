/*
 * Copyright (C) 2025 Răzvan Albu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>
 */

package com.stario.launcher.sheet.briefing.rss

import com.prof18.rssparser.RssParser
import com.prof18.rssparser.model.RssChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture

class RSSHelperKt {
    companion object {
        private const val MAX_CONCURRENT_FEEDS = 4

        private val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_FEEDS)
        )

        @JvmStatic
        fun parseFeed(parser: RssParser, url: String): CompletableFuture<RssChannel> =
            scope.future {
                parser.getRssChannel(url)
            }
    }
}