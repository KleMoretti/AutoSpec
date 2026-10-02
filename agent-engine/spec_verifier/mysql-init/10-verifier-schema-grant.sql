-- The verifier creates one random database per execution and drops it in
-- finally.  Escape the underscore so this grant is limited to the literal
-- autospec_verify_ prefix rather than matching arbitrary database names.
GRANT ALL PRIVILEGES ON `autospec_verify\_%`.* TO 'verify'@'%';
FLUSH PRIVILEGES;
