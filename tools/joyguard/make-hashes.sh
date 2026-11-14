#!/usr/bin/env bash
# Writes .githooks/guard-hashes from the owner's private guard list: a fresh random salt and one
# salted SHA-256 hash per guarded word. The words themselves never leave the private file and
# are never printed. Run on the owner's PC after editing the private list, then commit the file.
#   bash tools/joyguard/make-hashes.sh [private-list] [output]
set -u
repo=$(git rev-parse --show-toplevel) || exit 1
priv="${1:-$(git config --get joyguard.patternsFile || echo "$HOME/.joycreator-private/guard-patterns.txt")}"
out="${2:-$repo/.githooks/guard-hashes}"
[ -f "$priv" ] || { echo "make-hashes: no private list at $priv" >&2; exit 1; }
perl -MDigest::SHA=sha256_hex -e '
  my ($priv, $out) = @ARGV;
  open(my $r, "<", "/dev/urandom") or die "no random source\n"; read($r, my $b, 16); close $r;
  my $salt = unpack("H*", $b);
  open(my $p, "<", $priv) or die "cannot read the private list\n";
  my (@h, $bad);
  while (my $l = <$p>) {
    $l =~ s/[\r\n]+$//; next if $l =~ /^\s*(#|$)/;
    (my $w = $l) =~ s/\x5cb//g;    # drop the word-boundary marks
    if ($w !~ /^[A-Za-z0-9]{3,}$/) { $bad++; next; }
    my $whole = ($l =~ /^\x5cb.*\x5cb$/) ? " word" : "";    # \bword\b: whole word only
    push @h, sha256_hex($salt . lc $w) . $whole;
  }
  die "make-hashes: $bad private line(s) are not a plain word (letters/digits, optional word-boundary marks); fix the list\n" if $bad;
  die "make-hashes: no words\n" unless @h;
  open(my $o, ">", $out) or die "cannot write $out\n"; binmode $o;
  print $o "# Joy Creator guard, for machines without the owner'"'"'s private list (see .githooks/guard.pl).\n";
  print $o "# Salted SHA-256 hashes of the guarded words: sha256(salt + lowercase word). Not the words.\n";
  print $o "# A hash marked \"word\" matches a whole word only; an unmarked one also inside longer names.\n";
  print $o "# Regenerate with: bash tools/joyguard/make-hashes.sh\n";
  print $o "salt $salt\n";
  print $o "$_\n" for sort @h;
  close $o;
  print scalar(@h), " hashes written\n";
' "$priv" "$out"
