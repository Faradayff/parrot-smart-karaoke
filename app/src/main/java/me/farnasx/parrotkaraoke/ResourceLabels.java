package me.farnasx.parrotkaraoke;

import android.content.Context;

import me.farnasx.parrotkaraoke.net.Diagnostics;

/**
 * {@link Diagnostics.Labels} backed by the app's string resources, so the
 * free-text fragments of the diagnostic check list follow the device locale
 * (English by default, Spanish when the device is in Spanish).
 */
public final class ResourceLabels implements Diagnostics.Labels {

    private final Context ctx;

    public ResourceLabels(Context ctx) {
        this.ctx = ctx;
    }

    public String invalidUrl() {
        return ctx.getString(R.string.diag_detail_invalid_url);
    }

    public String implied() {
        return ctx.getString(R.string.diag_detail_implied);
    }

    public String notChecked() {
        return ctx.getString(R.string.diag_detail_not_checked);
    }

    public String tcpUnreachable(String target) {
        return ctx.getString(R.string.diag_detail_tcp_unreachable, target);
    }

    public String dnsFailed(String host) {
        return ctx.getString(R.string.diag_detail_dns_failed, host);
    }

    public String portOpen(String label) {
        return ctx.getString(R.string.diag_detail_port_open, label);
    }

    public String portClosed(String label) {
        return ctx.getString(R.string.diag_detail_port_closed, label);
    }

    public String noResponse() {
        return ctx.getString(R.string.diag_detail_no_response);
    }

    public String diagnosticUnavailable() {
        return ctx.getString(R.string.diag_detail_unavailable);
    }

    public String unknownError() {
        return ctx.getString(R.string.diag_detail_unknown_error);
    }

    public String noAuthHeader() {
        return ctx.getString(R.string.diag_detail_no_auth_header);
    }
}
