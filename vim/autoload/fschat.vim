" fschat autoload functions. See plugin/fschat.vim for the overview.

function! s:err(msg) abort
  echohl WarningMsg
  echom 'fschat: ' . a:msg
  echohl None
endfunction

" Resolve the daemon's local TCP port. Priority:
"   1. g:fschat_port            (explicit override)
"   2. g:fschat_port_file       (explicit file)
"   3. a .fschat-port marker found by walking up from the open file's directory
"      (the daemon drops this in its chat root, so the file you open selects the
"       account automatically)
"   4. ~/.config/fschat/port    (single-account fallback)
function! fschat#port() abort
  if exists('g:fschat_port')
    return g:fschat_port
  endif
  if exists('g:fschat_port_file') && filereadable(g:fschat_port_file)
    return str2nr(readfile(g:fschat_port_file)[0])
  endif
  let l:marker = findfile('.fschat-port', expand('%:p:h') . ';')
  if !empty(l:marker)
    return str2nr(readfile(fnamemodify(l:marker, ':p'))[0])
  endif
  let l:pf = expand('~/.config/fschat/port')
  if filereadable(l:pf)
    return str2nr(readfile(l:pf)[0])
  endif
  return 0
endfunction

" Replace a buffer's whole contents, toggling 'modifiable' as needed.
function! s:set_lines(buf, lines) abort
  call setbufvar(a:buf, '&modifiable', 1)
  silent! call deletebufline(a:buf, 1, '$')
  call setbufline(a:buf, 1, a:lines)
  call setbufvar(a:buf, '&modifiable', 0)
endfunction

" Open a chat file: connect, load the transcript, and split off a compose pane.
function! fschat#attach() abort
  if exists('b:fschat_attached')
    return
  endif
  let l:port = fschat#port()
  if l:port <= 0
    call s:err('no daemon port; start the daemon or set g:fschat_port')
    return
  endif

  let l:handle = ch_open('127.0.0.1:' . l:port, {'mode': 'json', 'callback': function('fschat#on_push')})
  if ch_status(l:handle) !=# 'open'
    call s:err('cannot connect to daemon on port ' . l:port)
    return
  endif

  let l:reply = ch_evalexpr(l:handle, {'c': 'open', 'path': expand('%:p')})
  if type(l:reply) != type({}) || !get(l:reply, 'ok', 0)
    call s:err('open failed: ' . string(type(l:reply) == type({}) ? get(l:reply, 'error', '?') : l:reply))
    call ch_close(l:handle)
    return
  endif

  let l:transcript = bufnr('%')
  let b:fschat_attached = 1
  let b:fschat_channel = l:reply.channelId
  let b:fschat_me = l:reply.meId
  let b:fschat_handle = l:handle

  call s:set_lines(l:transcript, l:reply.lines)
  setlocal filetype=chat
  setlocal buftype=nofile
  setlocal nomodifiable
  setlocal nomodified

  let g:fschat_bufs[b:fschat_channel] = l:transcript

  command! -buffer FschatEdit call fschat#edit()
  command! -buffer FschatDelete call fschat#delete()
  command! -buffer FschatReload call fschat#reload()
  command! -buffer FschatCompose call fschat#compose()

  " open_compose switches focus to the compose buffer, so record the compose
  " bufnr back onto the transcript buffer explicitly.
  let l:compose = s:open_compose(l:transcript, l:handle, b:fschat_channel)
  call setbufvar(l:transcript, 'fschat_compose_bufnr', l:compose)
endfunction

" Jump to the compose pane and start typing (usable any time).
function! fschat#compose() abort
  if exists('b:fschat_compose')
    startinsert
    return
  endif
  let l:cbuf = getbufvar(bufnr('%'), 'fschat_compose_bufnr', -1)
  if l:cbuf != -1 && bufwinid(l:cbuf) != -1
    call win_gotoid(bufwinid(l:cbuf))
    startinsert
  endif
endfunction

" Create the small compose pane below the transcript. Returns its bufnr.
function! s:open_compose(transcript, handle, channel) abort
  botright 4split
  enew
  let b:fschat_compose = 1
  let b:fschat_channel = a:channel
  let b:fschat_handle = a:handle
  let b:fschat_transcript = a:transcript
  let b:fschat_edit_target = ''
  setlocal buftype=nofile bufhidden=wipe noswapfile
  setlocal filetype=fschat_compose
  setlocal winfixheight
  execute 'silent! file fschat://compose/' . a:channel

  inoremap <buffer> <CR> <Esc>:call fschat#send()<CR>
  nnoremap <buffer> <CR> :call fschat#send()<CR>
  augroup fschat_compose
    autocmd! * <buffer>
    autocmd BufWriteCmd <buffer> call fschat#send()
  augroup END

  let l:compose = bufnr('%')
  " Enter insert mode via a 0ms timer: `startinsert` issued directly from the
  " file-load autocommand does not reliably stick, but a timer callback runs in
  " the main loop where it does.
  call timer_start(0, function('s:enter_compose', [win_getid()]))
  return l:compose
endfunction

function! s:enter_compose(winid, timer) abort
  if win_gotoid(a:winid)
    startinsert
  endif
endfunction

" Apply a daemon-pushed transcript update (id-0 message).
function! fschat#on_push(ch, msg) abort
  if type(a:msg) != type({})
    return
  endif
  let l:cid = get(a:msg, 'channelId', '')
  if !has_key(g:fschat_bufs, l:cid)
    return
  endif
  let l:buf = g:fschat_bufs[l:cid]
  if !bufexists(l:buf)
    return
  endif

  let l:kind = get(a:msg, 'push', '')
  call setbufvar(l:buf, '&modifiable', 1)
  if l:kind ==# 'append'
    call appendbufline(l:buf, '$', a:msg.lines)
  elseif l:kind ==# 'replace'
    call deletebufline(l:buf, a:msg.fromLine, a:msg.toLine)
    call appendbufline(l:buf, a:msg.fromLine - 1, a:msg.lines)
  elseif l:kind ==# 'reset' || l:kind ==# 'renamed'
    " Full re-render (contact/block change, or a rename).
    call deletebufline(l:buf, 1, '$')
    call setbufline(l:buf, 1, a:msg.lines)
    if l:kind ==# 'renamed'
      " The group's file moved; rename the (nofile) buffer cosmetically to match.
      let l:winid = bufwinid(l:buf)
      if l:winid != -1
        call win_execute(l:winid, 'keepalt file ' . fnameescape(a:msg.path))
      endif
    endif
  endif
  call setbufvar(l:buf, '&modifiable', 0)
  call s:follow(l:buf)
endfunction

" Keep the transcript window scrolled to the newest message.
function! s:follow(buf) abort
  let l:winid = bufwinid(a:buf)
  if l:winid != -1
    call win_execute(l:winid, 'normal! G')
  endif
endfunction

" Send the compose buffer as a new message, or as an edit if one is pending.
function! fschat#send() abort
  if !exists('b:fschat_compose')
    return
  endif
  let l:content = join(getline(1, '$'), "\n")
  if l:content =~# '^\s*$'
    return
  endif
  let l:reply = ''
  if !empty(b:fschat_edit_target)
    call ch_sendexpr(b:fschat_handle,
          \ {'c': 'edit', 'channelId': b:fschat_channel,
          \  'messageId': b:fschat_edit_target, 'content': l:content})
    let b:fschat_edit_target = ''
  else
    " Use evalexpr so a /command's feedback (info/error) comes back to echo.
    let l:reply = ch_evalexpr(b:fschat_handle,
          \ {'c': 'send', 'channelId': b:fschat_channel, 'content': l:content})
  endif
  silent! %delete _
  startinsert
  if type(l:reply) == type({})
    if has_key(l:reply, 'error')
      call s:err(l:reply.error)
    elseif has_key(l:reply, 'info')
      echom 'fschat: ' . l:reply.info
    endif
  endif
endfunction

" Find the #msg block the cursor is in (searches backward).
function! fschat#msg_under_cursor() abort
  let l:lnum = search('^#msg id=', 'bcnW')
  if l:lnum == 0
    return {}
  endif
  let l:line = getline(l:lnum)
  return {
        \ 'id': matchstr(l:line, 'id=\zs\S\+'),
        \ 'author': matchstr(l:line, 'author=\zs\S\+'),
        \ 'lnum': l:lnum,
        \ }
endfunction

" Edit the (owned) message under the cursor: load it into the compose pane.
function! fschat#edit() abort
  let l:m = fschat#msg_under_cursor()
  if empty(l:m)
    call s:err('no message under cursor')
    return
  endif
  if l:m.author !=# b:fschat_me
    call s:err('you can only edit your own messages')
    return
  endif
  " Body is everything after the header until the next #-sigil line.
  let l:start = l:m.lnum + 1
  let l:end = l:start
  while l:end <= line('$') && getline(l:end) !~# '^#'
    let l:end += 1
  endwhile
  let l:body = getline(l:start, l:end - 1)

  let l:cbuf = b:fschat_compose_bufnr
  let l:cwin = bufwinid(l:cbuf)
  if l:cwin == -1
    call s:err('compose pane not found')
    return
  endif
  call win_gotoid(l:cwin)
  call setbufvar(l:cbuf, '&modifiable', 1)
  silent! %delete _
  call setline(1, l:body)
  let b:fschat_edit_target = l:m.id
  startinsert
endfunction

" Delete the (owned) message under the cursor.
function! fschat#delete() abort
  let l:m = fschat#msg_under_cursor()
  if empty(l:m)
    call s:err('no message under cursor')
    return
  endif
  if l:m.author !=# b:fschat_me
    call s:err('you can only delete your own messages')
    return
  endif
  call ch_sendexpr(b:fschat_handle,
        \ {'c': 'delete', 'channelId': b:fschat_channel, 'messageId': l:m.id})
endfunction

" Re-fetch the whole transcript from the daemon.
function! fschat#reload() abort
  let l:reply = ch_evalexpr(b:fschat_handle, {'c': 'open', 'path': expand('%:p')})
  if type(l:reply) == type({}) && get(l:reply, 'ok', 0)
    call s:set_lines(bufnr('%'), l:reply.lines)
  endif
endfunction
