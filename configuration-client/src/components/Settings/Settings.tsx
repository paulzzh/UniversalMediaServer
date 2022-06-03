import { TextInput, Checkbox, Button, Group, Box } from '@mantine/core';
import { useForm } from '@mantine/form';
import { showNotification } from '@mantine/notifications';
// import { updateNotification } from '@mantine/notifications';
import _ from 'lodash';
import { useEffect, useRef, useState } from "react";
const axios = require('axios').default;

export default function Settings() {
  const [isLoading, setLoading] = useState(true);
  // todo: get rid of these initial values
  let translations = { 'NetworkTab.71': 'Server name', 'NetworkTab.72': 'Append profile name' };
  const translationsRef = useRef(translations);

  const initialValues = {
    server_name: 'Universal Media Server',
    append_profile_name: false,
  };

  const openGitHubNewIssue = () => {
    window.location.href = 'https://github.com/UniversalMediaServer/UniversalMediaServer/issues/new';
  };

  const [configuration, setConfiguration] = useState(initialValues);

  const form = useForm({ initialValues });

  // Code here will run just like componentDidMount
  useEffect(() => {
    showNotification({
      id: 'data-loading',
      color: 'indigo',
      loading: true,
      title: 'Loading',
      message: 'Loading your configuration...',
      autoClose: false,
      disallowClose: true,
    });

    Promise.all([
      axios.get('/configuration-api/settings'),
      axios.get('/configuration-api/i18n'),
    ])
      .then(function (response: any[]) {
        showNotification({
          id: 'data-loading',
          color: 'teal',
          title: 'Success',
          message: 'Configuration was loaded',
          autoClose: 3000,
        });
        // todo: fix notification updating
        // updateNotification({
        //   id: 'data-loading',
        //   color: 'teal',
        //   title: 'Done',
        //   message: 'Configuration was loaded',
        //   autoClose: 3000,
        // });

        // merge defaults with what we receive, which might only be non-default values
        const userConfig = _.merge(initialValues, response[0].data);
        translationsRef.current = response[1].data;
        /**
         * Work around a bug in the Java JSON conversion where
         * booleans are parsed as strings.
         *
         * @see https://github.com/mikolajmitura/java-properties-to-json/issues/64
         */
        _.each(userConfig, (value, key: string) => {
          if (
            typeof value === 'string' &&
            (
              value.toLowerCase() === 'false' ||
              value.toLowerCase() === 'true'
            )
          ) {
            userConfig[key] = value.toLowerCase() === 'true';
          }
        });
        setConfiguration(userConfig);
        form.setValues(configuration);
      })
      .catch(function (error: Error) {
        console.log(error);
        showNotification({
          id: 'data-loading',
          color: 'red',
          title: 'Error',
          message: 'Your configuration was not received from the server. Please click here to report the bug to us.',
          onClick: () => { openGitHubNewIssue(); },
          autoClose: 3000,
        });
        // todo: fix notification updating
        // updateNotification({
        //   id: 'data-loading',
        //   color: 'red',
        //   title: 'Error',
        //   message: 'Your configuration was not received from the server. Please click here to report the bug to us.',
        //   onClick: () => { openGitHubNewIssue(); },
        //   autoClose: 3000,
        // });
      })
      .then(function () {
        form.validate();
        setLoading(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSubmit = (values: typeof form.values) => {
    setLoading(true);
    axios.post('/configuration-api/', values)
      .then(function () {
        showNotification({
          title: 'Saved',
          message: 'Your configuration changes were saved successfully',
          onClick: () => { openGitHubNewIssue(); },
        })
      })
      .catch(function (error: Error) {
        console.log(error);
        showNotification({
          color: 'red',
          title: 'Error',
          message: 'Your configuration changes were not saved. Please click here to report the bug to us.',
          onClick: () => { openGitHubNewIssue(); },
        })
      })
      .then(function () {
        setLoading(false);
      });
  };

  return (
    <Box sx={{ maxWidth: 300 }} mx="auto">
      <form onSubmit={form.onSubmit(handleSubmit)}>
        <TextInput
          required
          label={translationsRef.current['NetworkTab.71']}
          name="server_name"
          {...form.getInputProps('server_name')}
        />

        <Checkbox
          mt="md"
          label={translationsRef.current['NetworkTab.72']}
          {...form.getInputProps('append_profile_name', { type: 'checkbox' })}
        />

        <Group position="right" mt="md">
          <Button type="submit" loading={isLoading}>Submit</Button>
        </Group>
      </form>
    </Box>
  );
}