# Builds markdup_edge_cases.sam from adam-core/src/test/resources/bqsr1.sam:
# every record, plus, for every tenth pair (k = pair index):
#   k % 10 == 0  a copy of the pair under a new name: an exact score tie
#   k % 10 == 1  a secondary alignment of each read, 500 bases on
#   k % 10 == 2  read 1 again as a fragment at the pair's left position,
#                so the left position holds fragments beside pairs
#   k % 10 == 3  read 2 again as a reverse-strand fragment
#   k % 10 == 4  a supplementary alignment of read 1, 700 bases on
#   k % 10 == 5  two copies of the pair 3000 bases on, one with every
#                quality at phred 14, one with 30 bases at phred 30: the
#                score threshold of 15 decides which is kept
#   k % 10 == 6  two copies of the pair 6000 bases on with both reads on
#                the reverse strand, read 2 of the second copy 10 bases
#                further on: distinct right positions, so neither is a
#                duplicate
#   k % 10 == 7  two copies of the pair 9000 bases on, the second with
#                read 1 clipped 5S95M from 5 bases on and read 2 clipped
#                95M5S: the same unclipped five prime ends, so duplicates
# usage: awk -f make_edge_cases.awk bqsr1.sam > markdup_edge_cases.sam
# (test_fixtures.sh does this into its work directory)

function bit(x, b) { return int(x / b) % 2 }

function emit(name, flag, pos, rnext, pnext, tlen) {
    emitq(name, flag, pos, rnext, pnext, tlen, $11)
}

function emitq(name, flag, pos, rnext, pnext, tlen, qual,    out, i) {
    out = name "\t" flag "\t" $3 "\t" pos "\t" $5 "\t" $6 "\t" rnext "\t" pnext "\t" tlen "\t" $10 "\t" qual
    for (i = 12; i <= NF; i++) out = out "\t" $i
    print out
}

function repeat(c, n,    s) { s = ""; while (n-- > 0) s = s c; return s }

BEGIN { FS = OFS = "\t" }

/^@/ { print; next }

{
    print
    k = int(n / 2); first = (n % 2 == 0); n++
    if (k % 10 == 0)
        emit("tie_" $1, $2, $4, $7, $8, $9)
    else if (k % 10 == 1)
        emit($1, $2 + 256, $4 + 500, $7, $8, $9)
    else if (k % 10 == 2 && first)
        emit("frag_" $1, bit($2, 16) * 16, $4, "*", 0, 0)
    else if (k % 10 == 3 && !first)
        emit("rfrag_" $1, 16, $4, "*", 0, 0)
    else if (k % 10 == 4 && first)
        emit($1, $2 + 2048, $4 + 700, $7, $8, $9)
    else if (k % 10 == 5) {
        len = length($10)
        emitq("q14_" $1, $2, $4 + 3000, $7, $8 + 3000, $9, repeat("/", len))
        emitq("q30_" $1, $2, $4 + 3000, $7, $8 + 3000, $9, repeat("?", 30) repeat("#", len - 30))
    } else if (k % 10 == 6) {
        # set this read's and its mate's reverse-strand bits
        rr = $2 + (bit($2, 16) ? 0 : 16) + (bit($2, 32) ? 0 : 32)
        emit("rrA_" $1, rr, $4 + 6000, $7, $8 + 6000, $9)
        emit("rrB_" $1, rr, $4 + 6000 + (first ? 0 : 10), $7, $8 + 6000 + (first ? 10 : 0), $9)
    } else if (k % 10 == 7 && $6 == "100M") {
        emit("clipA_" $1, $2, $4 + 9000, $7, $8 + 9000, $9)
        cigar = $6
        $6 = bit($2, 16) ? "95M5S" : "5S95M"
        emit("clipB_" $1, $2, $4 + 9000 + (bit($2, 16) ? 0 : 5), $7, $8 + 9000 + (bit($2, 16) ? 5 : 0), $9)
        $6 = cigar
    }
}
