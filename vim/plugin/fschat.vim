" fschat.vim — filesystem-native chat in Vim.
"
" Talks to the local fschat daemon over a Vim JSON channel (localhost TCP). The
" daemon is the authority on transcript content; this plugin is thin glue:
" it opens a channel, forwards user intent, and applies pre-computed line ops
" the daemon pushes.
"
" Requires Vim 8.0+ with +channel +job.
"
" Config:
"   g:fschat_port       explicit daemon port (overrides everything)
"   g:fschat_port_file  path to the daemon's port file
"                       (default: ~/.config/fschat/port)

if exists('g:loaded_fschat')
  finish
endif
let g:loaded_fschat = 1

if !has('channel')
  echohl WarningMsg
  echom 'fschat: this Vim lacks +channel; the plugin is disabled'
  echohl None
  finish
endif

" channelId -> transcript bufnr, for routing daemon pushes.
if !exists('g:fschat_bufs')
  let g:fschat_bufs = {}
endif
