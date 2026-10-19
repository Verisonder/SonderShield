//go:build !windows && !linux

package main

func newPlatform() platform { return &otherPlatform{dir: portableDataDir()} }

func localeFromOS() (lang, country string) { return "en", "" }

func instanceDir(p platform) string { return p.dataDir() }
