#!/usr/bin/env perl
# Joy Creator guard: keeps the owner's private words (his real name, personal email,
# phone serials) out of the repository. See tasks/GIT_PRACTICE.md section 1.
#
# Two ways to know the words, best first:
#   1. The private list on the owner's PC (never in the repo): $JOYGUARD_PATTERNS_FILE, else
#      git config joyguard.patternsFile, else ~/.joycreator-private/guard-patterns.txt.
#      One case-insensitive regex per line.
#   2. Anywhere else (a fresh clone, another machine): .githooks/guard-hashes, salted SHA-256
#      hashes of the words. Every word in the checked text is hashed and compared; the words
#      themselves are never stored in the repository.
#
# It never prints what it found, only where (file and line, or commit). If the guard itself
# cannot run (no list, a broken file) it warns and lets the work through: it blocks only on a
# real hit, so it can never lock anyone out.
#
# Usage: guard.pl staged             - every staged file and file name (pre-commit)
#        guard.pl message FILE       - a commit message (commit-msg)
#        guard.pl stream [LABEL]     - git log -p text on stdin, e.g. outgoing commits (pre-push)
# Exit: 0 clean (or guard unavailable), 1 hit found.
use strict;
use warnings;
use File::Basename qw(dirname);
use Digest::SHA qw(sha256_hex);
use File::Temp qw(tempfile);

my $here = dirname($0);
my $mode = shift @ARGV // '';

sub warnpass { print STDERR "guard: $_[0] - not checking, letting this through.\n"; exit 0; }

# ---- load the words ------------------------------------------------------------------------
my (@rx, %hash, $salt);
my $private = $ENV{JOYGUARD_PATTERNS_FILE} // '';
if ($private eq '') {
    $private = `git config --get joyguard.patternsFile 2>/dev/null` // '';
    $private =~ s/[\r\n]+$//;
}
if ($private eq '') {
    my $home = $ENV{HOME} // $ENV{USERPROFILE} // '';
    $private = "$home/.joycreator-private/guard-patterns.txt";
}
if (!$ENV{JOYGUARD_IGNORE_PRIVATE} && -f $private && open(my $pf, '<', $private)) {
    while (my $l = <$pf>) {
        $l =~ s/[\r\n]+$//;
        next if $l =~ /^\s*(#|$)/;
        my $r = eval { qr/$l/i };
        push @rx, $r if $r;
    }
    close $pf;
}
if (!@rx) {
    my $hf = "$here/guard-hashes";
    open(my $hh, '<', $hf) or warnpass("no private list and no guard-hashes file");
    while (my $l = <$hh>) {
        $l =~ s/[\r\n]+$//;
        next if $l =~ /^\s*(#|$)/;
        if ($l =~ /^salt\s+(\S+)/) { $salt = $1; next; }
        # "<hash> word": a whole word only, like \bword\b in the private list (underscore and digits
        # count as part of a word, so CONST_NAME or name2 is a different word). "<hash>" alone: also
        # found inside longer runs (camelCase pieces, words joined by underscores).
        $hash{ lc $1 } = (defined $2 && $2 eq 'word') ? 'word' : 'part' if $l =~ /^([0-9a-fA-F]{64})(?:\s+(\w+))?/;
    }
    close $hh;
    warnpass("guard-hashes has no salt or no hashes") unless defined $salt && %hash;
}

# ---- the check ------------------------------------------------------------------------------
my %seen;    # token -> 0/1, cached for the whole run
sub line_hit {
    my ($line) = @_;
    if (@rx) {
        for my $r (@rx) { return 1 if $line =~ $r; }
        return 0;
    }
    # whole words, split the way \b splits them (letters, digits and underscore belong together)
    while ($line =~ /([A-Za-z0-9_]+)/g) {
        my $k = lc $1;
        next if length($k) < 3;
        $seen{"w$k"} //= $hash{ sha256_hex($salt . $k) } ? 1 : 0;
        return 1 if $seen{"w$k"};
    }
    # pieces: alnum runs, then camelCase, letter and digit runs inside them ("part" hashes only)
    my @tok;
    push @tok, $1 while $line =~ /([A-Za-z0-9]+)/g;
    push @tok, $1 while $line =~ /([A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+)/g;
    for my $t (@tok) {
        my $k = lc $t;
        next if length($k) < 3;
        $seen{"p$k"} //= (($hash{ sha256_hex($salt . $k) } // '') eq 'part') ? 1 : 0;
        return 1 if $seen{"p$k"};
    }
    return 0;
}

# a file name with its private words hidden, safe to print
sub mask {
    my ($s) = @_;
    if (@rx) { $s =~ s/$_/***/g for @rx; return $s; }
    $s =~ s{([A-Za-z0-9_]+)}{ line_hit($1) ? "***" : $1 }ge;
    return $s;
}

my $hits = 0;
sub report {
    my ($where) = @_;
    $hits++;
    print STDERR "BLOCKED: a private word of the owner's is in $where\n" if $hits <= 20;
}

sub scan_text {    # returns the line numbers that hold a private word
    my ($text) = @_;
    my (@lines, $n);
    $n = 0;
    for my $line (split /\n/, $text) { $n++; push @lines, $n if line_hit($line); }
    return @lines;
}

sub finish {
    my ($what) = @_;
    if ($hits) {
        print STDERR "  ...and " . ($hits - 20) . " more places\n" if $hits > 20;
        print STDERR "\nThe owner is JoyRaptor. Use that name, studio\@joycreator.cc for email and\n";
        print STDERR "'the sandbox phone' / 'the Note 20' for devices, then $what again.\n";
        print STDERR "Never use --no-verify. If you think this is a false alarm, say so in your report.\n";
        exit 1;
    }
    exit 0;
}

if ($mode eq 'staged') {
    my @paths = grep { length } split /\x00/, `git diff --cached --name-only -z --diff-filter=ACMR`;
    exit 0 unless @paths;
    for my $p (@paths) {
        if ($p eq '.githooks/_patterns') {
            report("$p (this file is retired: the list now lives privately on the owner's PC)");
            next;
        }
        report("the file name " . mask($p)) if line_hit($p);
    }
    my @check = grep { $_ ne '.githooks/guard-hashes' && $_ ne '.githooks/_patterns' && !/[\r\n]/ } @paths;
    if (@check) {
        # one cat-file process for every file, fed from a file: a process per file, or a
        # question-and-answer pipe, costs seconds per hundred files on Windows
        my ($tfh, $tname) = tempfile('joyguard-XXXXXX', TMPDIR => 1, UNLINK => 1);
        binmode $tfh;
        print {$tfh} ":$_\n" for @check;
        close $tfh;
        open(my $out, '-|', "git cat-file --batch < \"$tname\"") or warnpass('cannot read the staged files');
        binmode $out;
        for my $p (@check) {
            my $head = <$out>;
            last unless defined $head;
            next if $head =~ / missing\s*$/;
            my ($size) = $head =~ / (\d+)\s*$/;
            next unless defined $size;
            my $blob = '';
            while (length($blob) < $size) {
                my $got = read($out, $blob, $size - length($blob), length($blob));
                last unless $got;
            }
            read($out, my $nl, 1);
            next if index(substr($blob, 0, 8000), "\x00") >= 0;    # binary file
            report(mask($p) . " line $_") for scan_text($blob);
        }
        close $out;
    }
    finish('commit');
}
elsif ($mode eq 'message') {
    my $f = shift @ARGV or warnpass('no message file given');
    open(my $mf, '<', $f) or warnpass('cannot read the message');
    local $/;
    my $msg = <$mf> // '';
    close $mf;
    $msg =~ s/^# -+ >8 -+\r?\n.*//ms;    # drop the diff that commit -v appends
    report("the commit message, line $_") for scan_text($msg);
    finish('commit');
}
elsif ($mode eq 'stream') {
    # stdin: git log --format='commit %H%n%an <%ae>%n%cn <%ce>%n%B' -p
    my $label = shift @ARGV // 'commit';
    my ($commit, $file, $indiff) = ('?', '', 0);
    while (my $line = <STDIN>) {
        $line =~ s/[\r\n]+$//;
        if ($line =~ /^commit ([0-9a-f]{40})$/) { ($commit, $file, $indiff) = (substr($1, 0, 10), '', 0); next; }
        if ($line =~ /^diff --git /) { ($indiff, $file) = (1, '?'); next; }
        if (!$indiff) { report(mask($label) . " $commit (its message or author)") if line_hit($line); next; }
        if ($line =~ m{^\+\+\+ (?:b/)?(.*)}) {
            $file = $1;
            report(mask($label) . " $commit, the file name " . mask($file)) if line_hit($file);
            next;
        }
        next unless $line =~ /^\+/;
        report(mask($label) . " $commit, " . mask($file)) if line_hit(substr($line, 1));
    }
    finish('push');
}
else {
    warnpass("unknown mode '$mode'");
}
