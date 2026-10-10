#!/bin/bash
set -u
REAL_GIT=${JUPITER_GIT_REAL:-/usr/bin/git.real}
TRAILER='Co-authored-by: Jupiter IDE <340693705+jupiter-ide@users.noreply.github.com>'
all=("$@")
parsed=()
i=0
commit=0
while (( i < ${#all[@]} )); do
  arg=${all[i]}
  case "$arg" in
    -c|-C|--git-dir|--work-tree|--namespace|--config-env)
      if (( i + 1 >= ${#all[@]} )); then
        break
      fi
      parsed+=("$arg" "${all[i + 1]}")
      ((i += 2))
      ;;
    --git-dir=*|--work-tree=*|--namespace=*|--config-env=*|--exec-path=*)
      parsed+=("$arg")
      ((i += 1))
      ;;
    --exec-path|--html-path|--man-path|--info-path|--version|-v|--help|-h)
      exec "$REAL_GIT" "${all[@]}"
      ;;
    -p|-P|--paginate|--no-pager|--no-replace-objects|--bare|--literal-pathspecs|--glob-pathspecs|--noglob-pathspecs|--icase-pathspecs|--no-optional-locks|--no-advice|--no-lazy-fetch|--no-sparse-index)
      parsed+=("$arg")
      ((i += 1))
      ;;
    commit)
      commit=1
      ((i += 1))
      break
      ;;
    *)
      break
      ;;
  esac
done
rest=("${all[@]:i}")
if (( commit )); then
  exec "$REAL_GIT" "${parsed[@]}" -c trailer.Co-authored-by.key=Co-authored-by -c trailer.Co-authored-by.ifexists=addIfDifferent -c trailer.Co-authored-by.ifmissing=add commit --trailer "$TRAILER" "${rest[@]}"
fi
exec "$REAL_GIT" "${all[@]}"
