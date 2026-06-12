" Attach the fschat machinery when a .chat file is opened.
if exists('b:fschat_attached')
  finish
endif
call fschat#attach()
