" Syntax highlighting for fschat .chat transcripts.
if exists('b:current_syntax')
  finish
endif

syntax match fschatMeta    /^#fschat\>.*/
syntax match fschatChannel /^#channel\>.*/
syntax match fschatMe      /^#me\>.*/
syntax match fschatMsg     /^#msg\>.*/
syntax match fschatSys     /^#sys\>.*/
syntax match fschatCompose /^#===.*/
syntax match fschatHandle  /author=\S\+/ containedin=fschatMsg
syntax match fschatDeleted /\[deleted\]/

highlight default link fschatMeta    Comment
highlight default link fschatChannel Comment
highlight default link fschatMe      Comment
highlight default link fschatMsg     Title
highlight default link fschatSys     Special
highlight default link fschatCompose NonText
highlight default link fschatHandle  Identifier
highlight default link fschatDeleted NonText

let b:current_syntax = 'chat'
