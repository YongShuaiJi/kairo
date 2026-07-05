create unique index uk_project_organization_name
    on project(organization_id, name);

create unique index uk_application_project_name
    on application(project_id, name);
