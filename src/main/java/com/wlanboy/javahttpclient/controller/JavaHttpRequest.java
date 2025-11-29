package com.wlanboy.javahttpclient.controller;

import org.springframework.lang.NonNull;

public record JavaHttpRequest(@NonNull String url, @NonNull Boolean copyHeaders) {}

