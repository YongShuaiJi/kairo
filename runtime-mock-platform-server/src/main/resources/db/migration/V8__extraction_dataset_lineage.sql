alter table dataset_version alter column source_session_id drop not null;
alter table dataset_version add column source_type varchar(64) not null default 'RECORDING_SESSION';
alter table dataset_version add column source_ref varchar(128);

update dataset_version
   set source_ref = source_session_id
 where source_ref is null;

alter table extraction_result add column dataset_version_id varchar(128);
alter table extraction_result
    add constraint fk_extraction_result_dataset_version
    foreign key (dataset_version_id) references dataset_version(id);

create index idx_extraction_result_dataset_version
    on extraction_result(dataset_version_id);
