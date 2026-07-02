update operation_plan
   set status = case
       when status = 'WAITING_APPROVAL' then 'DRAFT'
       when status in ('APPROVED', 'SCHEDULED', 'OBSERVING') then 'RUNNING'
       when status in ('PARTIALLY_SUCCEEDED', 'CANCELLED', 'EXPIRED') then 'FAILED'
       else status
   end,
       updated_at = current_timestamp
 where status in (
       'WAITING_APPROVAL',
       'APPROVED',
       'SCHEDULED',
       'OBSERVING',
       'PARTIALLY_SUCCEEDED',
       'CANCELLED',
       'EXPIRED'
   );
