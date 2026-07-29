<?php

declare(strict_types=1);

use Illuminate\Contracts\Console\Kernel;
use Pterodactyl\Models\Server;
use Pterodactyl\Repositories\Wings\DaemonCommandRepository;

$panelRoot = getenv('PANEL_ROOT') ?: '/app';
require $panelRoot . '/vendor/autoload.php';
$app = require $panelRoot . '/bootstrap/app.php';
$app->make(Kernel::class)->bootstrap();

$command = trim(implode(' ', array_slice($argv, 1)));
if ($command === '') {
    fwrite(STDERR, "Usage: php send-command.php <console command>\n");
    exit(2);
}

$server = Server::query()
    ->where('external_id', 'sls-lite-local-velocity')
    ->firstOrFail();

$app->make(DaemonCommandRepository::class)
    ->setServer($server)
    ->send($command);

printf("Sent to %s: %s\n", $server->uuid, $command);

