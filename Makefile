.DEFAULT_GOAL := all
.ONESHELL:
SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c

GRADLEW ?= ./gradlew
GRADLE_ARGS ?=
RELEASE_DATE ?= $(shell date -u +%Y%m%d)
export RELEASE_DATE

.PHONY: all debug install installDebug lint test compile clean release help

all:
	$(GRADLEW) :vector-app:assembleRelease $(GRADLE_ARGS)

debug:
	$(GRADLEW) :vector-app:assembleDebug $(GRADLE_ARGS)

install:
	$(GRADLEW) :vector-app:install $(GRADLE_ARGS)

installDebug:
	$(GRADLEW) :vector-app:installDebug $(GRADLE_ARGS)

lint:
	$(GRADLEW) :vector-app:lintRelease $(GRADLE_ARGS)

test:
	$(GRADLEW) test $(GRADLE_ARGS)

compile:
	$(GRADLEW) :vector:compileDebugKotlin $(GRADLE_ARGS)

clean:
	$(GRADLEW) clean $(GRADLE_ARGS)

release:
	@tag="$$RELEASE_DATE"
	[[ "$$tag" =~ ^[0-9]{8}$$ ]] || { echo 'Release date must be YYYYMMDD.' >&2; exit 1; }
	formatted="$${tag:0:4}-$${tag:4:2}-$${tag:6:2}"
	[ "$$(date -u -d "$$formatted" +%Y%m%d)" = "$$tag" ]
	[ -z "$$(git status --porcelain)" ] || { echo 'Commit or stash your changes before releasing.' >&2; exit 1; }
	branch=$$(git symbolic-ref --quiet --short HEAD) || { echo 'Check out a branch before releasing.' >&2; exit 1; }
	remote=$$(git config --get "branch.$$branch.remote") || { echo 'Release branch must track a remote branch.' >&2; exit 1; }
	upstream=$$(git config --get "branch.$$branch.merge") || { echo 'Release branch must track a remote branch.' >&2; exit 1; }
	[ "$$remote" != '.' ] || { echo 'Release branch must track a remote branch.' >&2; exit 1; }
	git fetch "$$remote" --tags
	git merge-base --is-ancestor '@{upstream}' HEAD || { echo 'Integrate upstream changes before releasing.' >&2; exit 1; }
	if git show-ref --verify --quiet "refs/tags/$$tag"; then
		echo "Tag $$tag already exists. Choose another RELEASE_DATE." >&2
		exit 1
	fi
	previous=$$(git tag --merged HEAD --list '[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]' | awk -v current="$$tag" '$$0 < current' | sort -r | sed -n '1p')
	changelog=
	if [ -n "$$previous" ]; then
		mapfile -t subjects < <(git log --no-merges --format='• %s' --invert-grep --grep='^Add F-Droid changelogs for ' "$$previous..HEAD")
		for i in "$${!subjects[@]}"; do
			candidate="$${changelog:+$$changelog$$'\n'}$${subjects[$$i]}"
			remaining=$$(( $${#subjects[@]} - i - 1 ))
			more=
			if [ "$$remaining" -gt 0 ]; then more=$$'\n'"…and $$remaining more"; fi
			if [ $$(( $${#candidate} + $${#more} )) -gt 500 ]; then
				changelog="$${changelog:+$$changelog$$'\n'}…and $$(( remaining + 1 )) more"
				break
			fi
			changelog=$$candidate
		done
	fi
	changelog=$${changelog:-Initial release.}
	dir=fastlane/metadata/android/en-US/changelogs
	mkdir -p "$$dir"
	base=$$(( (10#$$tag + 20000000) * 10 ))
	files=()
	for abi in 1 2 3 4; do
		file="$$dir/$$(( base + abi )).txt"
		printf '%s\n' "$$changelog" > "$$file"
		files+=("$$file")
	done
	git add -- "$${files[@]}"
	if ! git diff --cached --quiet; then
		git commit -m "Add F-Droid changelogs for $$tag"
	fi
	git -c push.followTags=false push "$$remote" "HEAD:$$upstream"
	git tag "$$tag"
	git push "$$remote" "refs/tags/$$tag"

help:
	@printf '%s\n' \
	  'make              Build release APKs for all supported ABIs' \
	  'make debug        Build debug APKs' \
	  'make install      Install the shrunk release build' \
	  'make installDebug Install the debug build' \
	  'make lint         Run release lint' \
	  'make test         Run the unit test suite across modules and variants' \
	  'make compile      Compile the app Kotlin code' \
	  'make clean        Remove Gradle build outputs' \
	  'make release      Commit store changelogs, tag today in UTC, push branch then tag' \
	  '                  Requires a clean checkout and a remote tracking branch' \
	  'Overrides: GRADLE_ARGS="--offline" RELEASE_DATE=20261001'
