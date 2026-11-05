#!/bin/sh

# do we have enough arguments?
if [ $# -lt 3 ]; then
    echo "Usage:"
    echo
    echo "./release.sh <release version> <development version> <milestone id>"
    exit 1
fi

# pick arguments
release=$1
devel=$2
milestone=$3

# get current branch
branch=$(git status -bs | awk '{ print $2 }' | awk -F'.' '{ print $1 }' | head -n 1)

# update changelog per Github milestone, in the existing CHANGES.md format
milestone_title=$(gh api repos/bigdatagenomics/adam/milestones/${milestone} --jq .title)
{
  echo "# ADAM Changelog #"
  echo
  echo "### Version ${release} ###"
  echo
  echo "**Closed issues:**"
  echo
  gh issue list --repo bigdatagenomics/adam --state closed --milestone "${milestone_title}" --limit 1000 \
    --json number,title,url --template '{{range .}} - {{.title}} [\#{{.number}}]({{.url}}){{"\n"}}{{end}}'
  echo
  echo "**Merged and closed pull requests:**"
  echo
  gh pr list --repo bigdatagenomics/adam --state merged --search "milestone:\"${milestone_title}\"" --limit 1000 \
    --json number,title,url,author --template '{{range .}} - {{.title}} [\#{{.number}}]({{.url}}) ([{{.author.login}}](https://github.com/{{.author.login}})){{"\n"}}{{end}}'
  echo
  tail -n +2 CHANGES.md
} > CHANGES.new
mv CHANGES.new CHANGES.md
git commit -a -m "Modifying changelog."

# update R version
sed -i -e "s/Version: [0-9.]*/Version: $1/g" adam-r/bdgenomics.adam/DESCRIPTION
git commit -a -m "Bumping R version to $1."

commit=$(git log --pretty=format:"%H" | head -n 1)
echo "releasing from ${commit} on branch ${branch}"

git push origin ${branch}

# do spark 4, scala 2.13 release
git checkout -b maint_spark4_2.13-${release} ${branch}

sed -i -e "s/  val adam = \".*\"/  val adam = \"${release}\"/" build.mill
git commit -a -m "Setting version to ${release}."
git tag adam-parent-spark4_2.13-${release}

./mill --ticker false __.test && \
  ./mill --ticker false mill.javalib.SonatypeCentralPublishModule/ && \
  ./mill --ticker false adam-distribution.dist

if [ $? != 0 ]; then
  echo "Releasing Spark 4, Scala 2.13 version failed."
  exit 1
fi

git push origin maint_spark4_2.13-${release} adam-parent-spark4_2.13-${release}

if [ $branch = "master" ]; then
  # if original branch was master, update versions on original branch
  git checkout ${branch}
  sed -i -e "s/  val adam = \".*\"/  val adam = \"${devel}\"/" build.mill
  git commit -a -m "Modifying build.mill for new development after ${release} release."
  git push origin ${branch}
fi
