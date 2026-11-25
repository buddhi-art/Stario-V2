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

package com.stario.launcher.sheet.briefing.configurator;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.prof18.rssparser.model.RssChannel;
import com.stario.launcher.R;
import com.stario.launcher.Stario;
import com.stario.launcher.sheet.briefing.dialog.page.feed.BriefingFeedList;
import com.stario.launcher.sheet.briefing.dialog.page.feed.Feed;
import com.stario.launcher.sheet.briefing.rss.RSSHelper;
import com.stario.launcher.themes.ThemedActivity;
import com.stario.launcher.ui.common.text.PulsingTextView;
import com.stario.launcher.ui.dialogs.ActionDialog;
import com.stario.launcher.ui.utils.UiUtils;
import com.stario.launcher.utils.Utils;

import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import carbon.view.SimpleTextWatcher;

public class BriefingConfigurator extends ActionDialog {
    private static final String TAG = "BriefingConfigurator";
    private static final long DEBOUNCE_DELAY = 300;
    private static final int JSOUP_TIMEOUT = 5000;

    private final BriefingFeedList list;

    private volatile Future<?> currentSearchTask;
    private volatile Feed validatedFeed;
    private volatile long searchGeneration;

    private Runnable debounceRunnable;
    private ViewGroup contentView;
    private PulsingTextView limit;
    private LinearLayout preview;
    private TextView title;
    private EditText query;

    public BriefingConfigurator(@NonNull ThemedActivity activity) {
        super(activity);

        this.list = BriefingFeedList.from(activity);
    }

    @Override
    protected @NonNull View inflateContent(LayoutInflater inflater) {
        contentView = (ViewGroup) inflater.inflate(R.layout.briefing_configurator, null);

        query = contentView.findViewById(R.id.query);
        preview = contentView.findViewById(R.id.preview);
        title = contentView.findViewById(R.id.title);
        limit = contentView.findViewById(R.id.limit);

        query.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(@NonNull CharSequence sequence,
                                      int start, int before, int count) {
                long generation = ++searchGeneration;

                if (debounceRunnable != null) {
                    UiUtils.removeUICallback(debounceRunnable);
                    debounceRunnable = null;
                }

                if (currentSearchTask != null && !currentSearchTask.isDone()) {
                    currentSearchTask.cancel(true);
                }

                String text = sequence.toString();

                if (text.isEmpty()) {
                    showStatus(null, false);

                    return;
                }

                String validUrl = null;
                if (isValidUrl(text)) {
                    validUrl = text;
                } else {
                    String potentialUrl = "https://" + text;

                    if (isValidUrl(potentialUrl)) {
                        validUrl = potentialUrl;
                    }
                }

                if (validUrl == null) {
                    showStatus(R.string.invalid_url, false);

                    return;
                }

                if (!Utils.isNetworkAvailable(activity)) {
                    showStatus(R.string.no_connection, false);

                    return;
                }

                String finalValidUrl = validUrl;
                debounceRunnable = () -> {
                    if (generation != searchGeneration || !isShowing()) {
                        return;
                    }
                    showStatus(R.string.searching, true);
                    currentSearchTask = Utils.submitTask(
                            new FeedDiscoveryTask(activity.getApplicationContext(),
                                    generation,
                                    new String[]{
                                            finalValidUrl,
                                            finalValidUrl.replaceAll("/$", "") + ".rss"
                                    })
                    );
                };

                UiUtils.postDelayed(debounceRunnable, DEBOUNCE_DELAY);
            }
        });

        contentView.findViewById(R.id.add).setOnClickListener((view) -> {
            view.startAnimation(AnimationUtils.loadAnimation(activity, R.anim.bounce_small));

            if (validatedFeed != null && validatedFeed.getTitle() != null &&
                    !validatedFeed.getTitle().isEmpty()) {
                boolean added = list.add(validatedFeed);

                if (added) {
                    BottomSheetBehavior<?> behavior = getBehavior();
                    behavior.setDraggable(false);
                    behavior.setState(BottomSheetBehavior.STATE_HIDDEN);

                    UiUtils.hideKeyboard(contentView);
                } else {
                    Toast.makeText(activity,
                            R.string.already_subscribed, Toast.LENGTH_LONG).show();
                }
            }
        });

        contentView.findViewById(R.id.paste).setOnClickListener((v) -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);

            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();

                if (clip != null && clip.getItemCount() > 0 &&
                        clip.getDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    CharSequence coerced = clip.getItemAt(0).coerceToText(activity);
                    String text = coerced != null ? coerced.toString() : "";

                    if (!text.isEmpty()) {
                        query.setText(text);
                        query.setSelection(text.length());
                    }
                }
            }
        });

        return contentView;
    }

    @Override
    public void show() {
        if (query != null) {
            query.setText(null);
        }

        super.show();
    }

    @Override
    public void dismiss() {
        searchGeneration++;

        if (debounceRunnable != null) {
            UiUtils.removeUICallback(debounceRunnable);
            debounceRunnable = null;
        }
        if (currentSearchTask != null && !currentSearchTask.isDone()) {
            currentSearchTask.cancel(true);
            currentSearchTask = null;
        }

        super.dismiss();
    }

    private boolean isValidUrl(String url) {
        return URLUtil.isValidUrl(url) && Patterns.WEB_URL.matcher(url).matches();
    }

    private void showStatus(Integer messageRes, boolean pulsating) {
        Resources resources = activity.getResources();

        if (preview != null) {
            preview.setVisibility(View.GONE);
        }

        if (limit != null) {
            if (messageRes != null) {
                limit.setText(resources.getString(messageRes));
                limit.setPulsating(pulsating);
                limit.setVisibility(View.VISIBLE);
            } else {
                limit.setVisibility(View.GONE);
            }
        }

        validatedFeed = null;
    }

    private void showPreview(@NonNull Feed feed) {
        validatedFeed = feed;

        title.setText(feed.getTitle());
        preview.setVisibility(View.VISIBLE);
        limit.setVisibility(View.GONE);
    }

    private class FeedDiscoveryTask implements Runnable {
        private final Stario context;
        private final long generation;
        private final String[] urls;

        private FeedDiscoveryTask(Stario context, long generation, String[] urls) {
            this.context = context;
            this.generation = generation;
            this.urls = urls;
        }

        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted() || generation != searchGeneration) {
                return;
            }

            for (String url : urls) {
                try {
                    Feed feed = attemptParse(url);

                    // after a network task, check for interruption
                    if (Thread.currentThread().isInterrupted() || generation != searchGeneration) {
                        return;
                    }

                    if (feed != null) {
                        contentView.post(() -> {
                            if (generation == searchGeneration && isShowing()) {
                                showPreview(feed);
                            }
                        });

                        return;
                    }

                    String discoveredFeedUrl = discoverFeedUrl(url);

                    if (Thread.currentThread().isInterrupted() || generation != searchGeneration) {
                        return;
                    }

                    if (discoveredFeedUrl != null) {
                        Feed discoveredFeed = attemptParse(discoveredFeedUrl);

                        if (Thread.currentThread().isInterrupted() || generation != searchGeneration) {
                            return;
                        }

                        if (discoveredFeed != null) {
                            contentView.post(() -> {
                                if (generation == searchGeneration && isShowing()) {
                                    showPreview(discoveredFeed);
                                }
                            });

                            return;
                        }
                    }
                } catch (IOException exception) {
                    Log.e(TAG, "IOException: " + exception.getMessage());
                }
            }

            contentView.post(() -> {
                if (generation == searchGeneration && isShowing()) {
                    showStatus(R.string.invalid_rss, false);
                }
            });
        }

        private Feed attemptParse(String url) {
            CompletableFuture<@NotNull RssChannel> streamFuture = null;

            try {
                streamFuture = RSSHelper.futureParse(url);
                String title = streamFuture.get(10, TimeUnit.SECONDS).getTitle();

                if (title == null) {
                    title = context.getString(R.string.unknown_feed);
                }

                return new Feed(title, url);
            } catch (InterruptedException | TimeoutException exception) {
                if (streamFuture != null) {
                    streamFuture.cancel(true);
                }

                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } catch (Exception exception) {
                if (streamFuture != null) {
                    streamFuture.cancel(true);
                }
            }

            return null;
        }

        private String discoverFeedUrl(String url) throws IOException {
            Document document = Jsoup.connect(url)
                    .userAgent(Utils.USER_AGENT)
                    .timeout(JSOUP_TIMEOUT)
                    .get();

            Elements nodes = document.select("link[type*=\"rss\"], link[type*=\"atom\"]");

            for (Element node : nodes) {
                if (node.hasAttr("href")) {
                    return node.attr("abs:href");
                }
            }

            return null;
        }
    }

    @Override
    protected boolean blurBehind() {
        return true;
    }

    @Override
    protected int getDesiredInitialState() {
        return BottomSheetBehavior.STATE_EXPANDED;
    }
}
