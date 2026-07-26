<?php

declare(strict_types=1);

use Carbon\CarbonImmutable;
use Illuminate\Contracts\Console\Kernel;
use Pterodactyl\Models\Allocation;
use Pterodactyl\Models\Egg;
use Pterodactyl\Models\Node;
use Pterodactyl\Models\Server;
use Pterodactyl\Models\User;
use Pterodactyl\Repositories\Wings\DaemonPowerRepository;
use Pterodactyl\Services\Allocations\AssignmentService;
use Pterodactyl\Services\Servers\ServerCreationService;

const EXTERNAL_ID = 'sls-lite-local-external-lobby';
const ALLOCATION_PORT = 25566;
const STARTUP_COMMAND = 'java -Xms128M -Xmx768M -Dterminal.jline=false '
    . '-Dterminal.ansi=true -jar {{SERVER_JARFILE}}';

$panelRoot = getenv('PANEL_ROOT') ?: '/var/www/pterodactyl';

require $panelRoot . '/vendor/autoload.php';

$app = require $panelRoot . '/bootstrap/app.php';
$app->make(Kernel::class)->bootstrap();

$node = Node::query()->findOrFail(1);
$owner = User::query()->where('username', 'admin')->firstOrFail();
$egg = Egg::query()->where('name', 'Paper')->firstOrFail();
$server = Server::query()->where('external_id', EXTERNAL_ID)->first();

if (!$server && in_array('--existing-only', $argv, true)) {
    printf("%s\n", json_encode(['exists' => false], JSON_PRETTY_PRINT));
    exit(0);
}

$allocation = Allocation::query()
    ->where('node_id', $node->id)
    ->where('ip', '0.0.0.0')
    ->where('port', ALLOCATION_PORT)
    ->first();

if (!$allocation) {
    $app->make(AssignmentService::class)->handle($node, [
        'allocation_ip' => '0.0.0.0',
        'allocation_ports' => [(string) ALLOCATION_PORT],
        'allocation_alias' => 'localhost',
    ]);

    $allocation = Allocation::query()
        ->where('node_id', $node->id)
        ->where('ip', '0.0.0.0')
        ->where('port', ALLOCATION_PORT)
        ->firstOrFail();
}

if (!$server) {
    if ($allocation->server_id !== null) {
        throw new RuntimeException(
            'Allocation 0.0.0.0:' . ALLOCATION_PORT . ' is already assigned.'
        );
    }

    $server = $app->make(ServerCreationService::class)->handle([
        'external_id' => EXTERNAL_ID,
        'name' => 'SLS-LITE External Lobby',
        'description' => 'Separate Paper lobby for SLS-LITE external-mode testing.',
        'owner_id' => $owner->id,
        'allocation_id' => $allocation->id,
        'egg_id' => $egg->id,
        'memory' => 1024,
        'swap' => 0,
        'disk' => 5000,
        'io' => 500,
        'cpu' => 200,
        'threads' => null,
        'oom_disabled' => true,
        'startup' => STARTUP_COMMAND,
        'image' => 'ghcr.io/pterodactyl/yolks:java_25',
        'database_limit' => 0,
        'allocation_limit' => 0,
        'backup_limit' => 1,
        'skip_scripts' => true,
        'start_on_completion' => false,
        'environment' => [
            'MINECRAFT_VERSION' => '26.1.2',
            'SERVER_JARFILE' => 'server.jar',
            'DL_PATH' => '',
            'BUILD_NUMBER' => '74',
        ],
    ]);
}

$server->forceFill(['startup' => STARTUP_COMMAND])->save();

if (in_array('--mark-installed', $argv, true)) {
    $server->forceFill([
        'status' => null,
        'installed_at' => CarbonImmutable::now(),
    ])->save();
}

$powerAction = null;
foreach (['start', 'stop', 'restart', 'kill'] as $action) {
    if (in_array('--' . $action, $argv, true)) {
        $powerAction = $action;
        break;
    }
}

if ($powerAction !== null) {
    $app->make(DaemonPowerRepository::class)->setServer($server)->send($powerAction);
}

$volume = '/var/lib/pterodactyl/volumes/' . $server->uuid;
printf(
    "%s\n",
    json_encode([
        'id' => $server->id,
        'exists' => true,
        'uuid' => $server->uuid,
        'identifier' => $server->uuidShort,
        'status' => $server->status,
        'allocation' => '0.0.0.0:' . ALLOCATION_PORT,
        'container_address' => $server->uuid . ':' . ALLOCATION_PORT,
        'volume' => $volume,
    ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES)
);
